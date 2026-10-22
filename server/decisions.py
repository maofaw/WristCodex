"""Bounded, typed native decisions. No RPCs or model execution in this module."""
import copy
import hashlib
import json
import math
import re


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
        separators=(',', ':')).encode()).hexdigest()


def bounded(value, maximum=16000):
    try:
        return len(json.dumps(value, ensure_ascii=False)) <= maximum
    except (ValueError, TypeError):
        return False


def questions_valid(questions):
    return (isinstance(questions, list) and 0 < len(questions) <= 6 and bounded(questions)
        and all(isinstance(q, dict) and isinstance(q.get('id'), str) and q['id']
            and isinstance(q.get('question'), str) and len(q['question']) <= 4000
            and not q.get('isSecret') and isinstance(q.get('options') or [], list)
            and len(q.get('options') or []) <= 10
            and all(isinstance(o, dict) and isinstance(o.get('label'), str)
                and o['label'].strip() and len(o['label']) <= 200
                and len(str(o.get('description') or '')) <= 1000 for o in (q.get('options') or []))
            for q in questions) and len({q['id'] for q in questions}) == len(questions))


def form_fields(schema):
    """Reject constraints we cannot render AND enforce; never flatten complex forms."""
    if not isinstance(schema, dict) or not bounded(schema):
        return None
    if set(schema) - {'type', 'properties', 'required', 'additionalProperties', 'title', 'description'}:
        return None
    properties, required = schema.get('properties'), schema.get('required', [])
    if (schema.get('type') != 'object' or not isinstance(properties, dict) or not 0 < len(properties) <= 10
            or not isinstance(required, list) or any(not isinstance(k, str) or k not in properties for k in required)):
        return None
    fields = []
    common = {'type', 'title', 'description', 'default', 'enum', 'enumNames', 'oneOf', 'minLength', 'maxLength',
        'minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum', 'minItems', 'maxItems', 'uniqueItems', 'items'}
    for name, definition in properties.items():
        if (not isinstance(name, str) or not name or len(name) > 120 or not isinstance(definition, dict)
                or re.search(r'password|passwd|secret|access.?token|verification.?code|验证码|口令', name, re.I)
                or set(definition) - common):
            return None
        field = copy.deepcopy(definition)
        kind = field.get('type')
        if kind not in ('string', 'boolean', 'integer', 'number', 'array'):
            return None
        if any(not isinstance(field.get(k, ''), str) or len(field.get(k, '')) > 1000 for k in ('title', 'description')):
            return None
        if 'oneOf' in field:
            choices = field.pop('oneOf')
            if kind != 'string' or not isinstance(choices, list) or not choices or any(
                    not isinstance(o, dict) or set(o) - {'const', 'title'} or not isinstance(o.get('const'), str)
                    or not isinstance(o.get('title', o['const']), str) for o in choices):
                return None
            field['enum'] = [o['const'] for o in choices]
            field['enumNames'] = [o.get('title', o['const']) for o in choices]
        if kind == 'array':
            item = field.get('items')
            if not isinstance(item, dict) or set(item) - {'type', 'enum'} or item.get('type') != 'string':
                return None
            field['enum'] = item.get('enum')
        if 'enum' in field and (not isinstance(field['enum'], list) or not 0 < len(field['enum']) <= 10
                or any(not isinstance(x, str) or not x.strip() or len(x) > 200 for x in field['enum'])
                or len(set(field['enum'])) != len(field['enum']) or kind not in ('string', 'array')):
            return None
        if kind == 'array' and not field.get('enum'):
            return None
        if 'enumNames' in field and (not isinstance(field['enumNames'], list)
                or len(field['enumNames']) != len(field.get('enum', []))
                or any(not isinstance(x, str) or not x.strip() or len(x) > 200 for x in field['enumNames'])):
            return None
        for key in ('minLength', 'maxLength', 'minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum', 'minItems', 'maxItems'):
            if key in field and (type(field[key]) not in (int, float) or not math.isfinite(field[key])):
                return None
        field.update(id=name, required=name in required, title=field.get('title') or name)
        fields.append(field)
    return fields


def validate_form(fields, response):
    if not isinstance(response, dict) or set(response) - {f['id'] for f in fields} or not bounded(response):
        raise ValueError('表单字段无效')
    for field in fields:
        key, kind = field['id'], field['type']
        if key not in response:
            if field['required']:
                raise ValueError('请填写全部必填项')
            continue
        value = response[key]
        valid = (isinstance(value, str) if kind == 'string' else type(value) is bool if kind == 'boolean'
            else type(value) is int if kind == 'integer' else type(value) in (int, float) and math.isfinite(value)
            if kind == 'number' else isinstance(value, list) and all(isinstance(x, str) for x in value))
        if not valid:
            raise ValueError('表单数据类型无效')
        if kind in ('string', 'array'):
            low, high = ('minLength', 'maxLength') if kind == 'string' else ('minItems', 'maxItems')
            if not field.get(low, 0) <= len(value) <= min(field.get(high, 4000 if kind == 'string' else 10), 4000 if kind == 'string' else 10):
                raise ValueError('表单长度超出范围')
            values = [value] if kind == 'string' else value
            if field.get('enum') and any(x not in field['enum'] for x in values):
                raise ValueError('请选择原始表单选项')
            if kind == 'array' and field.get('uniqueItems') and len(set(value)) != len(value):
                raise ValueError('表单选项不能重复')
        if kind in ('number', 'integer'):
            if (value < field.get('minimum', -math.inf) or value > field.get('maximum', math.inf)
                    or value <= field.get('exclusiveMinimum', -math.inf) or value >= field.get('exclusiveMaximum', math.inf)):
                raise ValueError('数字超出申请范围')
    return copy.deepcopy(response)


def permission_valid(value):
    if not isinstance(value, dict) or not value or set(value) - {'network', 'fileSystem'} or not bounded(value, 10000):
        return False
    network = value.get('network')
    if network is not None and (not isinstance(network, dict) or set(network) - {'enabled'}
            or type(network.get('enabled')) is not bool):
        return False
    fs = value.get('fileSystem')
    if fs is not None:
        if not isinstance(fs, dict) or set(fs) - {'read', 'write', 'entries', 'globScanMaxDepth'}:
            return False
        for key in ('read', 'write'):
            if fs.get(key) is not None and (not isinstance(fs[key], list) or len(fs[key]) > 32
                    or any(not isinstance(p, str) or not p or len(p) > 1000 for p in fs[key])):
                return False
        if fs.get('entries') is not None and (not isinstance(fs['entries'], list) or len(fs['entries']) > 32
                or any(not isinstance(e, dict) for e in fs['entries'])):
            return False
        depth = fs.get('globScanMaxDepth')
        if depth is not None and (type(depth) is not int or depth < 1):
            return False
    return bool(network or fs)


def validate_permissions(requested, response):
    if not isinstance(response, dict) or set(response) - {'permissions', 'scope'}:
        raise ValueError('权限回答无效')
    granted = response.get('permissions')
    if not isinstance(granted, dict) or set(granted) - set(requested):
        raise ValueError('不能授予申请之外的权限')
    for key, value in granted.items():
        asked = requested.get(key)
        if not isinstance(value, dict) or not isinstance(asked, dict) or set(value) - set(asked):
            raise ValueError('不能扩大权限范围')
        for field, selected in value.items():
            original = asked[field]
            if field == 'enabled' and type(selected) is not bool:
                raise ValueError('网络授权值必须为布尔值')
            if isinstance(original, list):
                if not isinstance(selected, list) or any(x not in original for x in selected):
                    raise ValueError('不能添加未申请的路径')
            elif field == 'globScanMaxDepth':
                if type(selected) is not int or not 1 <= selected <= original:
                    raise ValueError('不能扩大路径扫描范围')
            elif selected != original:
                raise ValueError('权限值与申请不符')
    scope = response.get('scope', 'turn')
    if scope not in ('turn', 'session'):
        raise ValueError('授权时长无效')
    return dict(permissions=copy.deepcopy(granted), scope=scope)


def decision_view(request, thread, turn, files):
    """Project one known human request. Internal server chores are not prompts."""
    if not isinstance(request, dict) or type(request.get('id')) not in (str, int):
        return None
    params = request.get('params') or {}
    if not isinstance(params, dict) or params.get('threadId') != thread.get('id'):
        return None
    method = request.get('method')
    if method in ('account/chatgptAuthTokens/refresh', 'attestation/generate', 'currentTime/read', 'item/tool/call'):
        return None
    reviewer = params.get('approvalsReviewer') or turn.get('reviewer') or (thread.get('currentPermissions') or {}).get('approvalsReviewer') or 'user'
    if method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval', 'item/permissions/requestApproval') and reviewer != 'user':
        return None
    identity = [thread['id'], params.get('turnId'), request['id']]
    view = dict(id='desktop:' + hashlib.sha256(json.dumps(identity).encode()).hexdigest(),
        thread_id=thread['id'], turn_id=params.get('turnId') or '', title=thread.get('title') or '电脑对话',
        summary=str(params.get('reason') or '电脑需要你决定')[:120], kind='manual',
        detail='请在电脑处理此类请求', supported=False, source='native_request')
    if method in ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval'):
        command = method.startswith('item/commandExecution')
        network = params.get('networkApprovalContext')
        detail = params.get('command') if command else files.get(params.get('itemId'))
        if isinstance(network, dict) and bounded(network, 4000):
            detail = '网络访问申请：\n' + json.dumps(network, ensure_ascii=False, indent=2) + ('\n\n' + detail if isinstance(detail, str) else '')
        options = params.get('availableDecisions')
        if options is None:
            options = ['accept', 'decline', 'cancel']
        labels = dict(accept='仅本次允许', acceptForSession='本次会话允许', decline='拒绝', cancel='取消任务')
        decisions = []
        if isinstance(options, list):
            for option in options[:12]:
                if isinstance(option, str) and option in labels:
                    decisions.append(dict(value=option, label=labels[option]))
                elif isinstance(option, dict) and bounded(option, 4000) and len(option) == 1:
                    amendment = option.get('acceptWithExecpolicyAmendment')
                    policy = option.get('applyNetworkPolicyAmendment')
                    if isinstance(amendment, dict) and amendment.get('execpolicy_amendment'):
                        label = '允许并保存命令规则'
                    elif isinstance(policy, dict) and policy.get('network_policy_amendment'):
                        label = '应用此网络规则'
                    else:
                        continue
                    decisions.append(dict(value='option:' + digest(option), label=label, detail=json.dumps(option, ensure_ascii=False)))
        if isinstance(detail, str) and detail and len(detail) <= 10000 and decisions:
            view.update(kind='approval', supported=True, detail=detail, decisions=decisions,
                summary=str(params.get('reason') or ('允许此网络访问？' if network else '允许执行这项电脑操作？' if command else '允许这些文件修改？'))[:120])
    elif method in ('item/tool/requestUserInput', 'wrist/asyncQuestions'):
        questions = params.get('questions')
        if questions_valid(questions):
            view.update(kind='questions', questions=copy.deepcopy(questions), supported=method != 'wrist/asyncQuestions',
                source='async_questions' if method == 'wrist/asyncQuestions' else 'native_request',
                summary=questions[0]['question'][:120])
            if not view['supported']:
                view['unavailable_reason'] = '异步问题回传尚未通过本机接口验证，请在电脑回答'
        elif method == 'wrist/asyncQuestions':
            view.update(source='async_questions',unavailable_reason='问题内容超出手表支持范围，请在电脑回答')
    elif method == 'item/tool/requestOptionPicker':
        view.update(summary=str(params.get('question') or '请选择电脑提供的选项')[:120],
            unavailable_reason='此选择卡片尚无已验证的回传接口，请在电脑选择')
    elif method == 'item/plan/requestImplementation':
        plan = params.get('planContent')
        if isinstance(plan, str) and plan.strip():
            view.update(kind='plan', detail=plan[:20000], clipped=len(plan) > 20000,
                summary='计划已生成，等待你决定', unavailable_reason='尚未验证可校验原计划的执行接口，请在电脑执行或提出修改')
    elif method == 'item/permissions/requestApproval':
        permissions = params.get('permissions')
        if permission_valid(permissions):
            view.update(kind='permissions', supported=True, permissions=copy.deepcopy(permissions),
                summary=str(params.get('reason') or '允许这些额外权限？')[:120], detail=json.dumps(permissions, ensure_ascii=False, indent=2))
    elif method == 'mcpServer/elicitation/request':
        sensitive = re.search(r'password|verification.?code|two.factor|one.time.?code|api[ _-]?key|auth[ _-]?token|access[ _-]?token|private[ _-]?key|credentials?|登录|验证码|密码|密钥|令牌|系统验证',
            str(params.get('message') or '') + json.dumps(params.get('requestedSchema') or {},ensure_ascii=False),re.I)
        fields = form_fields(params.get('requestedSchema')) if not sensitive and params.get('mode') in ('form', 'openai/form', 'openaiForm') else None
        if fields is not None:
            view.update(kind='form', supported=True, fields=fields, summary=str(params.get('message') or '请填写工具请求')[:120],
                detail=str(params.get('message') or '')[:4000])
        else:
            view['unavailable_reason'] = '此请求包含登录、系统验证或无法完整支持的表单，请在电脑处理'
    if view['turn_id'] != turn.get('turnId'):
        view.update(supported=False, unavailable_reason='此请求未绑定当前回合，请在电脑处理')
    view['version'] = digest([request, view.get('detail'), view.get('decisions')])
    return view
