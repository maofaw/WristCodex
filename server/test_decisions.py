"""Decision contracts use real native projections, without starting a model."""
import copy
import unittest
import tempfile
from unittest.mock import patch
from pathlib import Path
from codex_session import Assistant
from shared_assistant import SharedAssistant
from desktop_ipc import DeliveryUnknown
from test_shared_assistant import FakeDesktop
from decisions import validate_permissions

from desktop_ipc import Projection
from test_shared_assistant import state, projection, command


def request(method, **params):
    return dict(id=42, method=method, params=dict(threadId='pc1', turnId='turn1', **params))


class DecisionProjectionTests(unittest.TestCase):
    def test_completed_or_answered_async_questions_do_not_block_conversation(self):
        import json
        question=dict(id='async1',type='agentMessage',questions=[dict(title='第一题',options=['是','否']),dict(title='第二题',options=['继续','停止'])])
        first_id=json.dumps(['request_user_input_async','async1',0],separators=(',',':'))
        answer='<send_user_message_question_reply>'+json.dumps([dict(questionItemId=first_id,question='第一题',answer='是')])+'</send_user_message_question_reply>'
        raw=state(status='idle')
        turn=raw['turnHistory']['history']['entitiesByKey']['latest']
        turn['items']=[question]
        p=Projection('owner1');p.accept(dict(type='snapshot',revision=1,conversationState=raw))
        self.assertEqual(p.pending(),[])
        self.assertTrue(p.snapshot()['can_send'])
        turn['status']='inProgress'
        turn['items'].append(dict(id='reply',type='steeringUserMessage',status='accepted',input=[dict(type='text',text=answer)]))
        p.accept(dict(type='snapshot',revision=2,conversationState=raw))
        pending=p.pending()[0][0]['questions']
        self.assertEqual(len(pending),1)
        self.assertEqual(json.loads(pending[0]['id'])[-1],1,'unanswered question retains original index')
        self.assertNotIn('send_user_message_question_reply',str(p.message_items[-1]))
        self.assertNotIn('input',p.message_items[-1])
        turn['items'][-1]['status']='rejected'
        p.accept(dict(type='snapshot',revision=3,conversationState=raw))
        self.assertEqual(len(p.pending()[0][0]['questions']),2)
        turn['items'][-1].pop('status')
        p.accept(dict(type='snapshot',revision=4,conversationState=raw))
        self.assertEqual(len(p.pending()[0][0]['questions']),2)
        p.accept(dict(type='patches',baseRevision=4,revision=5,patches=[dict(op='add',
            path=['turnHistory','history','entitiesByKey','latest','items',1,'status'],value='accepted')]))
        self.assertEqual(len(p.pending()[0][0]['questions']),1)

    def test_unsupported_picker_and_large_async_prompt_remain_visible_on_watch(self):
        p = projection(requests=[request('item/tool/requestOptionPicker',question='选择导出方式',options=['文件','图片'])])
        self.assertEqual(p.pending()[0][0]['summary'],'选择导出方式')
        raw=state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items']=[dict(id='large',type='agentMessage',questions=[dict(title='x'*17000)])]
        p.accept(dict(type='snapshot',revision=2,conversationState=raw))
        self.assertEqual(len(p.pending()),1)
        self.assertFalse(p.pending()[0][0]['supported'])
        path=['turnHistory','history','entitiesByKey','latest','items',0]
        p.accept(dict(type='patches',baseRevision=2,revision=3,patches=[dict(op='replace',path=path+['text'],value='更多说明')]))
        self.assertEqual(len(p.pending()),1,'unrelated body updates must retain manual fallback')
        p.accept(dict(type='patches',baseRevision=3,revision=4,patches=[dict(op='replace',path=path+['questions'],value=[])]))
        self.assertEqual(p.pending(),[],'explicit resolution must clear the fallback marker')

    def test_numeric_permission_value_cannot_impersonate_boolean(self):
        with self.assertRaises(ValueError):
            validate_permissions(dict(network=dict(enabled=True)),dict(permissions=dict(network=dict(enabled=1))))

    def test_async_questions_are_not_lost_from_native_agent_messages(self):
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [dict(
            id='body1', type='agentMessage', phase='final_answer', text='',
            questions=[dict(title='翻页保留按钮吗？', options=['保留按钮', '只用滑动'])])]
        p = Projection('owner1')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        view = p.pending()[0][0]
        self.assertEqual(view['kind'], 'questions')
        self.assertEqual(view['source'], 'async_questions')
        self.assertEqual(view['questions'][0]['question'], '翻页保留按钮吗？')
        self.assertEqual(view['questions'][0]['options'][0]['label'], '保留按钮')
        # An arbitrary question-looking message is not a native interaction.
        p.message_items[0]['text'] = '请允许执行'
        self.assertEqual(len(p.pending()), 1)

    def test_streamed_async_question_fields_trigger_pending_and_resolution_removes_it(self):
        raw = state()
        raw['turnHistory']['history']['entitiesByKey']['latest']['items'] = [dict(
            id='body1', type='agentMessage', phase='final_answer', text='')]
        p = Projection('owner1')
        p.accept(dict(type='snapshot', revision=1, conversationState=raw))
        self.assertEqual(p.pending(), [])
        p.accept(dict(type='patches', baseRevision=1, revision=2, patches=[dict(op='add',
            path=['turnHistory', 'history', 'entitiesByKey', 'latest', 'items', 0, 'questions'],
            value=[dict(title='是否继续？', options=['是', '否'])])]))
        self.assertEqual(len(p.pending()), 1)
        p.accept(dict(type='patches', baseRevision=2, revision=3, patches=[dict(op='replace',
            path=['turnHistory', 'history', 'entitiesByKey', 'latest', 'items', 0, 'questions'], value=[])]))
        self.assertEqual(p.pending(), [])

    def test_plan_is_a_distinct_versioned_decision_not_a_question(self):
        p = projection(status='idle', requests=[request('item/plan/requestImplementation', planContent='# 计划\n先测试再修改')])
        first = p.pending()[0][0]
        self.assertEqual(first['kind'], 'plan')
        self.assertEqual(first['detail'], '# 计划\n先测试再修改')
        self.assertFalse(first['supported'], 'native atomic plan execution is not verified yet')
        p.requests[0]['params']['planContent'] += '\n新增验收'
        changed = p.pending()[0][0]
        self.assertEqual(first['id'], changed['id'])
        self.assertNotEqual(first['version'], changed['version'])

    def test_permission_request_keeps_exact_scope_and_hides_automatic_review(self):
        permissions = dict(network=dict(enabled=True), fileSystem=dict(read=['C:/work/readme'], write=['C:/work/out']))
        p = projection(requests=[request('item/permissions/requestApproval', reason='读取并导出', permissions=permissions)])
        view = p.pending()[0][0]
        self.assertEqual(view['kind'], 'permissions')
        self.assertEqual(view['permissions'], permissions)
        self.assertIn('C:/work/out', view['detail'])
        p.data['currentPermissions']['approvalsReviewer'] = 'auto_review'
        self.assertEqual(p.pending(), [])

    def test_simple_form_supports_typed_fields_and_secret_form_stays_manual(self):
        schema = dict(type='object', required=['name', 'enabled', 'count', 'choices'], properties=dict(
            name=dict(type='string', maxLength=60), enabled=dict(type='boolean'), count=dict(type='integer', minimum=1, maximum=5),
            choices=dict(type='array', items=dict(type='string', enum=['a', 'b']), minItems=1)))
        p = projection(requests=[request('mcpServer/elicitation/request', mode='form', message='配置导出', requestedSchema=schema)])
        view = p.pending()[0][0]
        self.assertEqual(view['kind'], 'form')
        self.assertEqual([f['type'] for f in view['fields']], ['string', 'boolean', 'integer', 'array'])
        p.requests[0]['params']['requestedSchema']['properties']['name']['format'] = 'password'
        view = p.pending()[0][0]
        self.assertFalse(view['supported'])
        self.assertEqual(view['kind'], 'manual')
        p.requests[0]['params']['requestedSchema']=dict(type='object',properties=dict(api_key=dict(type='string',title='API key',description='API token')))
        p.requests[0]['params']['message']='Configure connector credentials'
        self.assertEqual(p.pending()[0][0]['kind'],'manual')

    def test_network_only_prompt_does_not_require_fake_shell_command(self):
        p = projection(requests=[request('item/commandExecution/requestApproval',
            networkApprovalContext=dict(host='example.test', protocol='https', port=443),
            availableDecisions=['accept', 'acceptForSession', 'decline'])])
        view = p.pending()[0][0]
        self.assertEqual(view['kind'], 'approval')
        self.assertTrue(view['supported'])
        self.assertIn('example.test', view['detail'])
        self.assertEqual([d['value'] for d in view['decisions']], ['accept', 'acceptForSession', 'decline'])

    def test_operation_version_changes_for_choices_but_not_body_preview(self):
        p = projection(requests=[command()])
        version = p.pending()[0][0]['version']
        p.reply = 'new body'
        self.assertEqual(p.pending()[0][0]['version'], version)
        p.requests[0]['params']['availableDecisions'] = ['decline']
        self.assertNotEqual(p.pending()[0][0]['version'], version)

    def test_standalone_or_wrong_turn_request_cannot_gain_write_capability(self):
        p = projection(requests=[request('mcpServer/elicitation/request', mode='url', url='https://login.invalid', message='登录')])
        self.assertEqual(p.pending()[0][0]['kind'], 'manual')
        self.assertNotIn('url', p.pending()[0][0])
        p.requests[0]['params']['turnId'] = 'old-turn'
        self.assertFalse(p.pending()[0][0]['supported'])


class DecisionAnswerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.desktop = FakeDesktop()
        self.legacy = Assistant(Path(self.temp.name) / 'state.json')
        self.a = SharedAssistant(self.legacy, desktop=self.desktop)

    def tearDown(self):
        self.temp.cleanup()

    def pending(self, value):
        self.desktop.sessions['pc1'] = projection(requests=[value])
        return self.a.snapshot(True, 'pc1')['pending'][0]

    def test_policy_amendments_are_manual_for_clients_that_cannot_show_rule_details(self):
        value=command();value['params']['availableDecisions']=[dict(applyNetworkPolicyAmendment=dict(network_policy_amendment=dict(host='example.test',action='allow')))]
        view=self.pending(value)
        modern=self.a.client_snapshot(self.a.snapshot(True,'pc1'),6)['pending'][0]
        old=self.a.client_snapshot(self.a.snapshot(True,'pc1'),5)['pending'][0]
        self.assertTrue(modern['supported'])
        self.assertFalse(old['supported'])
        self.assertNotIn('decisions',old)
        with self.assertRaises(ValueError):
            self.a.answer(view['id'],view['decisions'][0]['value'],thread_id='pc1',turn_id='turn1')

    def test_definitively_rejected_answer_can_be_manually_submitted_after_reconnect(self):
        from desktop_ipc import DesktopUnavailable
        view=self.pending(command());native=self.desktop.request
        def reject(*args,**kwargs):raise DesktopUnavailable('no-client-found')
        self.desktop.request=reject
        with self.assertRaises(DesktopUnavailable):self.a.answer(view['id'],'accept',thread_id='pc1',turn_id='turn1',expected_version=view['version'])
        self.desktop.request=native
        self.a.answer(view['id'],'cancel',thread_id='pc1',turn_id='turn1',expected_version=view['version'])
        self.assertEqual(len(self.desktop.calls),1)
        self.assertEqual(self.desktop.calls[0][1]['decision'],'cancel')

    def test_new_type_is_manual_until_runtime_verification_and_for_old_clients(self):
        view = self.pending(request('item/permissions/requestApproval', permissions=dict(network=dict(enabled=True))))
        self.assertFalse(view['supported'])
        self.assertIn('验证', view['unavailable_reason'])
        self.a.verified_capabilities.add('permissions')
        modern = self.a.client_snapshot(self.a.snapshot(True, 'pc1'), 6)
        self.assertTrue(modern['pending'][0]['supported'])
        legacy = self.a.client_snapshot(self.a.snapshot(True, 'pc1'), 5)
        self.assertEqual(legacy['pending'][0]['kind'], 'manual')
        self.assertNotIn('permissions', legacy['pending'][0])

    def test_attention_list_reports_actual_capability_version_and_old_client_fallback(self):
        view=self.pending(request('item/permissions/requestApproval',permissions=dict(network=dict(enabled=True))))
        first=self.a.attention()['items'][0]
        self.assertFalse(first['supported'])
        self.assertEqual(first['version'],view['version'])
        self.assertIn('验证',first['unavailable_reason'])
        self.a.verified_capabilities.add('permissions')
        current=self.a.attention()
        self.assertTrue(current['items'][0]['supported'])
        self.assertEqual(self.a.client_snapshot(current,5)['items'][0]['kind'],'manual')

    def test_reconnected_client_cannot_reuse_evidence_from_an_old_build(self):
        self.desktop.connection_generation=1
        with patch('shared_assistant.verified_actions',return_value={'permissions'}):
            self.assertTrue(self.a.capabilities()['permissions']['available'])
        self.desktop.connection_generation=2
        with patch('shared_assistant.verified_actions',return_value=set()):
            self.assertFalse(self.a.capabilities()['permissions']['available'])

    def test_permission_grants_are_bounded_by_request_and_original_version(self):
        self.a.verified_capabilities = {'permissions'}
        view = self.pending(request('item/permissions/requestApproval', permissions=dict(
            network=dict(enabled=True), fileSystem=dict(read=['C:/a'], write=['C:/out']))))
        for response in [dict(permissions=dict(fileSystem=dict(write=['C:/other']))),
                         dict(permissions=dict(network=dict(enabled=True)), scope='forever')]:
            with self.assertRaises(ValueError):
                self.a.answer(view['id'], thread_id='pc1', turn_id='turn1', expected_version=view['version'], response=response)
        self.assertEqual(self.desktop.calls, [])
        self.a.answer(view['id'], thread_id='pc1', turn_id='turn1', expected_version=view['version'],
            response=dict(permissions=dict(fileSystem=dict(read=['C:/a'])), scope='turn'))
        method, params, _ = self.desktop.calls[-1]
        self.assertEqual(method, 'thread-follower-permissions-request-approval-response')
        self.assertEqual(params['response'], dict(permissions=dict(fileSystem=dict(read=['C:/a'])), scope='turn'))

    def test_request_mutation_and_missing_version_reject_before_write(self):
        self.a.verified_capabilities = {'permissions'}
        view = self.pending(request('item/permissions/requestApproval', permissions=dict(network=dict(enabled=True))))
        self.desktop.sessions['pc1'].requests[0]['params']['permissions']['fileSystem'] = dict(write=['C:/more'])
        for version in [None, view['version']]:
            with self.assertRaises((ValueError, RuntimeError)):
                self.a.answer(view['id'], thread_id='pc1', turn_id='turn1', expected_version=version,
                    response=dict(permissions={}))
        self.assertEqual(self.desktop.calls, [])

    def test_form_keeps_typed_content_and_rejects_extra_or_invalid_fields(self):
        self.a.verified_capabilities = {'forms'}
        view = self.pending(request('mcpServer/elicitation/request', mode='form', message='测试', requestedSchema=dict(
            type='object', required=['enabled', 'count'], properties=dict(enabled=dict(type='boolean'), count=dict(type='integer', minimum=1, maximum=3)))))
        for values in [dict(enabled='true', count=2), dict(enabled=True, count=4), dict(enabled=True, count=2, extra=True)]:
            with self.assertRaises(ValueError):
                self.a.answer(view['id'], thread_id='pc1', turn_id='turn1', expected_version=view['version'],
                    response=dict(action='accept', content=values))
        self.a.answer(view['id'], thread_id='pc1', turn_id='turn1', expected_version=view['version'],
            response=dict(action='accept', content=dict(enabled=True, count=2)))
        self.assertEqual(self.desktop.calls[-1][0], 'thread-follower-submit-mcp-server-elicitation-response')
        self.assertIs(self.desktop.calls[-1][1]['response']['content']['enabled'], True)

    def test_unknown_decision_delivery_is_not_retried_after_restart(self):
        view = self.pending(command())
        self.desktop.fail = True
        with self.assertRaises(DeliveryUnknown):
            self.a.answer(view['id'], 'accept', thread_id='pc1', turn_id='turn1', expected_version=view['version'])
        self.desktop.fail = False
        restored = SharedAssistant(self.legacy, desktop=self.desktop)
        with self.assertRaises(DeliveryUnknown):
            restored.answer(view['id'], 'accept', thread_id='pc1', turn_id='turn1', expected_version=view['version'])
        self.assertEqual(len(self.desktop.calls), 1)


if __name__ == '__main__':
    unittest.main()
