<?php
// Standalone diagnostic for PHP 7.4+. Upload the configured copy, not this template.
// Only synthetic test messages are written. This is not a production relay.
$probeConfig = [
    'access_key' => '__PROBE_ACCESS_KEY__',
    'mysql' => [
        'host' => 'localhost',
        'port' => 3306,
        'database' => '__PROBE_DATABASE__',
        'username' => '__PROBE_USERNAME__',
        'password' => '__PROBE_PASSWORD__',
    ],
];

ini_set('display_errors', '0');
header('Cache-Control: no-store, max-age=0');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header('X-Frame-Options: DENY');
header('X-CodexRM-Probe: 1');

function respond($value, $status = 200) {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}

function requireRun($input) {
    $run = isset($input['run']) ? $input['run'] : '';
    if (!is_string($run) || !preg_match('/\A[a-f0-9]{32}\z/D', $run)) {
        throw new RuntimeException('Invalid test run identifier');
    }
    return $run;
}

function mysqlConnection() {
    global $probeConfig;
    if (!class_exists('PDO') || !in_array('mysql', PDO::getAvailableDrivers(), true)) {
        throw new RuntimeException('PDO MySQL extension is unavailable');
    }
    $c = $probeConfig['mysql'];
    if (strpos($c['database'], '__PROBE_') === 0) {
        throw new RuntimeException('MySQL credentials have not been configured');
    }
    $pdo = new PDO(
        'mysql:host=' . $c['host'] . ';port=' . (int)$c['port'] . ';dbname=' . $c['database'] . ';charset=utf8mb4',
        $c['username'], $c['password'],
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_EMULATE_PREPARES => false, PDO::ATTR_TIMEOUT => 5]
    );
    return $pdo;
}

function dataDirectory() {
    global $probeConfig;
    static $directory;
    if ($directory !== null) return $directory;
    $suffix = substr(hash('sha256', __FILE__ . $probeConfig['access_key']), 0, 20);
    $bases = [];
    if (function_exists('sys_get_temp_dir')) $bases[] = @sys_get_temp_dir();
    $bases[] = __DIR__;
    foreach ($bases as $base) {
        if (!$base) continue;
        $candidate = $base . DIRECTORY_SEPARATOR . '.codexrm-probe-' . $suffix;
        if ((is_dir($candidate) || @mkdir($candidate, 0700)) && is_writable($candidate)) {
            // Fallback storage may be under the web root. Data files also have a PHP exit prefix.
            @file_put_contents($candidate . '/.htaccess', "Require all denied\nDeny from all\n");
            @file_put_contents($candidate . '/index.php', "<?php http_response_code(404); exit;\n");
            $directory = $candidate;
            return $directory;
        }
    }
    throw new RuntimeException('Neither a private temporary directory nor the script directory is writable');
}

function fileState($run, $operation, $input) {
    $path = dataDirectory() . '/run-' . $run . '.php';
    $creating = $operation === 'init';
    $f = @fopen($path, $creating ? 'x+b' : 'r+b');
    if (!$f) throw new RuntimeException('Cannot open persistent test file; run may not exist');
    $locked = false;
    try {
        if (!function_exists('flock') || !flock($f, LOCK_EX)) throw new RuntimeException('Exclusive file locking failed');
        $locked = true;
        $prefix = "<?php http_response_code(404); exit; ?>\n";
        if ($creating) {
            @chmod($path, 0600);
            $state = ['next' => 1, 'messages' => []];
        } else {
            $raw = stream_get_contents($f);
            if (substr($raw, 0, strlen($prefix)) !== $prefix) throw new RuntimeException('Test file prefix is corrupt');
            $state = json_decode(substr($raw, strlen($prefix)), true);
            if (!is_array($state) || !isset($state['next'], $state['messages'])) throw new RuntimeException('Test file state is corrupt');
        }
        if ($operation === 'init') {
            $result = ['run' => $run, 'backend' => 'file', 'file_lock' => true];
        } elseif ($operation === 'push') {
            if (count($state['messages']) >= 128) throw new RuntimeException('Diagnostic queue limit is 128 messages');
            $id = $state['next']++;
            $state['messages'][] = ['id' => $id, 'side' => $input['side'], 'payload' => $input['payload']];
            $result = ['id' => $id];
        } elseif ($operation === 'read') {
            $messages = [];
            foreach ($state['messages'] as $message) {
                if ($message['side'] === $input['side'] && $message['id'] > $input['after']) $messages[] = $message;
            }
            $result = ['messages' => $messages];
        } elseif ($operation === 'cleanup') {
            $result = ['removed' => true];
        } else {
            throw new RuntimeException('Unknown file operation');
        }
        if ($creating || $operation === 'push') {
            $data = $prefix . json_encode($state, JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
            rewind($f);
            if (!ftruncate($f, 0)) throw new RuntimeException('Cannot truncate test file');
            $offset = 0;
            while ($offset < strlen($data)) {
                $written = fwrite($f, substr($data, $offset));
                if ($written === false || $written === 0) throw new RuntimeException('Cannot write complete test file');
                $offset += $written;
            }
            if (!fflush($f)) throw new RuntimeException('Cannot flush test file');
        }
    } finally {
        if ($locked) flock($f, LOCK_UN);
        fclose($f);
    }
    if ($operation === 'cleanup' && !@unlink($path)) throw new RuntimeException('Cannot remove test file');
    return $result;
}

function mysqlState($run, $operation, $input) {
    $pdo = mysqlConnection();
    // The validated random run ID prevents touching any existing application table.
    $table = '`codexrm_probe_' . $run . '`';
    if ($operation === 'init') {
        $created = false;
        try {
            $pdo->exec('CREATE TABLE ' . $table . ' (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, side CHAR(1) NOT NULL, payload MEDIUMBLOB NOT NULL) ENGINE=InnoDB');
            $created = true;
            $pdo->beginTransaction();
            $sample = "CodexRM 事务测试 🧪\n" . str_repeat('x', 65536);
            $insert = $pdo->prepare('INSERT INTO ' . $table . ' (side,payload) VALUES (?,?)');
            $insert->execute(['a', $sample]);
            if ($pdo->query('SELECT payload FROM ' . $table . ' LIMIT 1')->fetchColumn() !== $sample) throw new RuntimeException('MySQL UTF-8/binary roundtrip failed');
            $pdo->rollBack();
            if ((int)$pdo->query('SELECT COUNT(*) FROM ' . $table)->fetchColumn() !== 0) throw new RuntimeException('MySQL rollback failed');
            return ['run' => $run, 'backend' => 'mysql', 'transaction_rollback' => true, 'payload_bytes' => strlen($sample), 'version' => $pdo->getAttribute(PDO::ATTR_SERVER_VERSION)];
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            if ($created) {
                try { $pdo->exec('DROP TABLE ' . $table); } catch (Throwable $ignored) {}
            }
            throw $e;
        }
    }
    if ($operation === 'push') {
        $statement = $pdo->prepare('INSERT INTO ' . $table . ' (side,payload) VALUES (?,?)');
        $statement->execute([$input['side'], $input['payload']]);
        return ['id' => (int)$pdo->lastInsertId()];
    }
    if ($operation === 'read') {
        $statement = $pdo->prepare('SELECT id,side,payload FROM ' . $table . ' WHERE side=? AND id>? ORDER BY id LIMIT 128');
        $statement->execute([$input['side'], $input['after']]);
        $messages = $statement->fetchAll(PDO::FETCH_ASSOC);
        foreach ($messages as &$message) $message['id'] = (int)$message['id'];
        return ['messages' => $messages];
    }
    if ($operation === 'cleanup') {
        $pdo->exec('DROP TABLE IF EXISTS ' . $table);
        return ['removed' => true];
    }
    throw new RuntimeException('Unknown MySQL operation');
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    try {
        $raw = file_get_contents('php://input', false, null, 0, 2097153);
        if (strlen($raw) > 2097152) respond(['ok' => false, 'error' => 'Diagnostic request limit is 2 MiB'], 413);
        $input = json_decode($raw, true);
        if (!is_array($input)) respond(['ok' => false, 'error' => 'Expected a JSON object; the host may have discarded the request body'], 400);
        $key = isset($input['key']) && is_string($input['key']) ? $input['key'] : '';
        if (strlen($probeConfig['access_key']) < 24 || strpos($probeConfig['access_key'], '__PROBE_') === 0) respond(['ok' => false, 'error' => 'Configure a random access key before using this template'], 503);
        if (!hash_equals($probeConfig['access_key'], $key)) respond(['ok' => false, 'error' => 'Unauthorized'], 401);
        $action = isset($input['action']) ? $input['action'] : '';
        $result = [];
        if ($action === 'info') {
            $functions = [];
            foreach (['json_encode', 'json_decode', 'hash_equals', 'random_bytes', 'flock', 'fopen', 'mkdir', 'usleep', 'flush', 'openssl_encrypt', 'curl_init'] as $name) $functions[$name] = function_exists($name);
            $limits = [];
            foreach (['max_execution_time', 'max_input_time', 'memory_limit', 'post_max_size', 'upload_max_filesize', 'output_buffering', 'zlib.output_compression'] as $name) $limits[$name] = ini_get($name);
            $result = ['php' => PHP_VERSION, 'sapi' => PHP_SAPI, 'functions' => $functions, 'limits' => $limits,
                'pdo_drivers' => class_exists('PDO') ? PDO::getAvailableDrivers() : [],
                'mysql_configured' => strpos($probeConfig['mysql']['database'], '__PROBE_') !== 0,
                'https_seen_by_php' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
                'forwarded_https' => isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https'];
        } elseif ($action === 'echo') {
            $payload = isset($input['payload']) && is_string($input['payload']) ? $input['payload'] : '';
            $auth = isset($_SERVER['HTTP_AUTHORIZATION']) ? $_SERVER['HTTP_AUTHORIZATION'] : (isset($_SERVER['REDIRECT_HTTP_AUTHORIZATION']) ? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] : '');
            $result = ['bytes' => strlen($payload), 'sha256' => hash('sha256', $payload),
                'authorization_header' => $auth === 'Bearer codexrm-probe-header',
                'custom_header' => isset($_SERVER['HTTP_X_PROBE_CHECK']) && $_SERVER['HTTP_X_PROBE_CHECK'] === 'codexrm-probe',
                'nonce' => isset($input['nonce']) ? $input['nonce'] : null];
        } elseif ($action === 'wait') {
            $seconds = isset($input['seconds']) ? max(0, min(5, (float)$input['seconds'])) : 2;
            if (!function_exists('usleep')) throw new RuntimeException('usleep is disabled');
            $start = microtime(true);
            usleep((int)($seconds * 1000000));
            $result = ['elapsed_seconds' => microtime(true) - $start];
        } elseif ($action === 'stream') {
            if (!function_exists('usleep') || !function_exists('flush')) throw new RuntimeException('Streaming functions are disabled');
            header('Content-Type: application/x-ndjson; charset=utf-8');
            header('X-Accel-Buffering: no');
            @ini_set('zlib.output_compression', '0');
            while (ob_get_level() > 0) { if (!@ob_end_flush()) break; }
            for ($i = 0; $i < 3; $i++) {
                echo json_encode(['ok' => true, 'tick' => $i]) . "\n";
                flush();
                if ($i < 2) usleep(700000);
            }
            exit;
        } elseif (strpos($action, 'queue_') === 0) {
            $operation = substr($action, 6);
            $backend = isset($input['backend']) ? $input['backend'] : '';
            if (!in_array($backend, ['file', 'mysql'], true)) throw new RuntimeException('Unknown queue backend');
            if (!in_array($operation, ['init', 'push', 'read', 'cleanup'], true)) throw new RuntimeException('Unknown queue action');
            $run = requireRun($input);
            $input['side'] = isset($input['side']) && $input['side'] === 'b' ? 'b' : 'a';
            $input['after'] = isset($input['after']) ? max(0, (int)$input['after']) : 0;
            if ($operation === 'push' && (!isset($input['payload']) || !is_string($input['payload']) || strlen($input['payload']) > 1048576)) throw new RuntimeException('Message must be a string no larger than 1 MiB');
            $result = $backend === 'mysql' ? mysqlState($run, $operation, $input) : fileState($run, $operation, $input);
        } else {
            respond(['ok' => false, 'error' => 'Unknown diagnostic action'], 400);
        }
        respond(['ok' => true, 'result' => $result]);
    } catch (Throwable $e) {
        $message = $e->getMessage();
        foreach ([$probeConfig['access_key'], $probeConfig['mysql']['password']] as $secret) {
            if ($secret !== '') $message = str_replace($secret, '[redacted]', $message);
        }
        respond(['ok' => false, 'error' => $message], 500);
    }
}
if ($_SERVER['REQUEST_METHOD'] !== 'GET') respond(['ok' => false, 'error' => 'Use GET for the page or POST for the diagnostic API'], 405);
header('Content-Type: text/html; charset=utf-8');
?>
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CodexRM · PHP 中继能力测试</title>
<style>
:root{color-scheme:light dark;font:16px/1.6 system-ui,sans-serif}body{max-width:920px;margin:40px auto;padding:0 20px}h1{font-size:1.7rem}p{color:#687384}input,button{font:inherit;padding:10px 14px;border:1px solid #8994a5;border-radius:8px}input{width:min(95%,430px)}button{cursor:pointer;margin:6px 8px 6px 0}button:disabled{opacity:.5;cursor:wait}#results{padding:0;list-style:none}#results li{border:1px solid #8994a550;border-left:4px solid #8994a5;border-radius:8px;padding:12px 16px;margin:12px 0;overflow-wrap:anywhere}#results .pass{border-left-color:#20a46b}#results .fail{border-left-color:#e05b5b}#results .warn{border-left-color:#d2a13b}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}small{color:#687384}#verdict{font-weight:600}label{display:block}
</style></head><body>
<h1>PHP 虚拟主机中继能力测试</h1>
<p>测试 HTTP 短轮询所需的鉴权、请求完整性、MySQL / 文件持久化和并发写入。只创建随机命名的测试表和文件，完成后自动清理。</p>
<label for="key">探针访问密钥</label><input id="key" type="password" autocomplete="off" spellcheck="false" placeholder="粘贴 access-key.txt 中的内容">
<div><button id="start">开始基本测试</button><button id="stream">测试流式输出（可选）</button><button id="export" disabled>下载测试报告</button></div>
<small>密钥仅保存在当前页面内存中。浏览器通过后，还需运行 check-probe.exe，确认程序客户端不会被主机的 JavaScript / Cookie 验证拦截。</small>
<p id="verdict" role="status"></p><ul id="results"></ul>
<script>
'use strict';
const endpoint = new URL(location.href); endpoint.search = ''; endpoint.hash = '';
const rows = [], el = id => document.getElementById(id);
function add(name, status, detail) {
    rows.push({name, status, detail});
    const li = document.createElement('li'); li.className = status;
    const title = document.createElement('strong'); title.textContent = `${status === 'pass' ? '通过' : status === 'fail' ? '失败' : '注意'} · ${name}`;
    const pre = document.createElement('pre'); pre.textContent = typeof detail === 'string' ? detail : JSON.stringify(detail, null, 2);
    li.append(title, pre); el('results').append(li); el('export').disabled = false;
}
function body(action, extra = {}, overrideKey) { return JSON.stringify({...extra, action, key: overrideKey === undefined ? el('key').value.trim() : overrideKey}); }
async function request(action, extra = {}, overrideKey) {
    const response = await fetch(endpoint, {method:'POST', cache:'no-store', credentials:'omit', redirect:'error', headers:{'Content-Type':'application/json','Authorization':'Bearer codexrm-probe-header','X-Probe-Check':'codexrm-probe'}, body:body(action, extra, overrideKey), signal:AbortSignal.timeout(20000)});
    const type = response.headers.get('content-type') || '';
    if (!type.includes('application/json')) throw new Error(`HTTP ${response.status} 返回 ${type || '未知类型'}，可能被浏览器验证或主机错误页面拦截`);
    const data = await response.json();
    if (!response.ok || !data.ok) { const error = new Error(data.error || `HTTP ${response.status}`); error.status = response.status; throw error; }
    return data.result;
}
function runId() { const bytes = new Uint8Array(16); crypto.getRandomValues(bytes); return Array.from(bytes, b => b.toString(16).padStart(2,'0')).join(''); }
async function queueTest(backend) {
    const run = runId(), base = {backend, run}; let created = false;
    try {
        const info = await request('queue_init', base); created = true;
        const expected = new Set();
        // Independent HTTP requests, both directions, with four concurrent writers.
        for (let offset = 0; offset < 16; offset += 4) {
            await Promise.all(Array.from({length:4}, (_, j) => {
                const i = offset + j, payload = `${run}:${i}:中继🧪`; expected.add(payload);
                return request('queue_push', {...base, side:i % 2 ? 'b':'a', payload});
            }));
        }
        const [a,b] = await Promise.all(['a','b'].map(side => request('queue_read', {...base, side})));
        const messages = [...a.messages, ...b.messages];
        if (messages.length !== 16 || new Set(messages.map(m => m.id)).size !== 16) throw new Error('并发写入后消息丢失或序号重复');
        for (const m of messages) { if (!expected.delete(m.payload)) throw new Error('消息内容重复或损坏'); }
        if (expected.size) throw new Error('消息未全部持久化');
        for (const list of [a.messages,b.messages]) {
            if (list.length !== 8 || list.some((m,i) => i > 0 && m.id <= list[i-1].id)) throw new Error('双向路由或消息顺序不正确');
        }
        const cursor = Math.max(...a.messages.map(m => m.id));
        if ((await request('queue_read', {...base, side:'a', after:cursor})).messages.length) throw new Error('游标读取重复返回旧消息');
        add(`${backend === 'mysql' ? 'MySQL':'文件'}跨请求双向消息 / 并发`, 'pass', {...info, messages:16, concurrent_writers:4});
        return true;
    } catch (error) { add(`${backend === 'mysql' ? 'MySQL':'文件'}持久化`, 'fail', error.message); return false; }
    finally {
        if (created) {
            try { await request('queue_cleanup', base); add(`${backend} 测试数据清理`, 'pass', '已删除本轮测试表或消息文件'); }
            catch (error) { add(`${backend} 测试数据清理`, 'warn', `${error.message}；未清理标识：${run}`); }
        }
    }
}
async function test(name, operation, required = true) {
    try { add(name, 'pass', await operation()); return true; }
    catch (error) { add(name, required ? 'fail':'warn', error.message); return false; }
}
function busy(value) { el('start').disabled = value; el('stream').disabled = value; el('key').disabled = value; }
el('start').onclick = async () => {
    if (!el('key').value.trim()) { el('key').focus(); return; }
    busy(true); rows.length = 0; el('results').replaceChildren(); el('verdict').textContent = '测试中…';
    try {
        const nonce = runId();
        const api = await test('POST JSON / 响应完整性', async () => {
            const value = await request('echo', {nonce, payload:'CodexRM 中继🧪'});
            if (value.nonce !== nonce || value.bytes !== new TextEncoder().encode('CodexRM 中继🧪').length) throw new Error('请求或响应内容被修改');
            add('Authorization / 自定义请求头', value.authorization_header && value.custom_header ? 'pass':'warn', value);
            return 'JSON 请求与响应正常';
        });
        if (!api) { el('verdict').textContent = 'HTTP API 未通过，请先检查主机限制或 PHP 错误。'; return; }
        const auth = await test('无效密钥必须拒绝', async () => {
            try { await request('echo', {}, 'invalid-' + nonce); }
            catch (error) { if (error.status === 401) return '错误密钥返回 HTTP 401'; throw error; }
            throw new Error('错误密钥仍可访问');
        });
        let info;
        const environment = await test('PHP 环境', async () => { info = await request('info'); return info; });
        const bytes = await test('16 KiB / 256 KiB / 1 MiB 请求', async () => {
            for (const size of [16384,262144,1048576]) {
                const payload = 'x'.repeat(size), value = await request('echo', {payload});
                const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload))), b => b.toString(16).padStart(2,'0')).join('');
                if (value.bytes !== size || value.sha256 !== digest) throw new Error(`${size} 字节请求被截断或修改`);
            }
            return '三种大小均完整到达 PHP，SHA-256 一致';
        });
        const mysql = info && info.mysql_configured ? await queueTest('mysql') : false;
        if (!info || !info.mysql_configured) add('MySQL', 'warn', '未配置数据库');
        const file = await queueTest('file');
        await test('2 秒请求存活（可选）', async () => { const value = await request('wait', {seconds:2}); if (value.elapsed_seconds < 1.8) throw new Error('等待被提前中止'); return value; }, false);
        el('verdict').textContent = api && auth && environment && bytes && (mysql || file)
            ? '浏览器基本测试通过。请再运行程序客户端测试，验证 Agent 可访问；结果尚不能证明长期稳定性或免费配额够用。'
            : '基本能力存在失败项，请查看报告。文件存储与 MySQL 至少需要一种通过。';
    } catch (error) { add('测试中断', 'fail', error.message); el('verdict').textContent = '测试未完成。'; }
    finally { busy(false); }
};
el('stream').onclick = async () => {
    if (!el('key').value.trim()) { el('key').focus(); return; }
    busy(true);
    try {
        const start = performance.now(), times = [], ticks = [];
        const response = await fetch(endpoint, {method:'POST', credentials:'omit', cache:'no-store', redirect:'error', headers:{'Content-Type':'application/json'}, body:body('stream'), signal:AbortSignal.timeout(15000)});
        if (!response.ok || !(response.headers.get('content-type') || '').includes('application/x-ndjson')) throw new Error(`流式请求失败，HTTP ${response.status}`);
        const reader = response.body.getReader(), decoder = new TextDecoder(); let pending = '';
        while (true) {
            const {value,done} = await reader.read(); if (done) break; pending += decoder.decode(value, {stream:true});
            let newline; while ((newline = pending.indexOf('\n')) >= 0) { const line = pending.slice(0,newline); pending = pending.slice(newline+1); if (line) { ticks.push(JSON.parse(line).tick); times.push(Math.round(performance.now()-start)); } }
        }
        if (ticks.join(',') !== '0,1,2') throw new Error('流式响应不完整');
        add('流式响应（可选）', times[2]-times[0] >= 900 ? 'pass':'warn', {arrival_ms:times, note:'若同时到达，说明中间层缓冲；短轮询仍可工作。'});
    } catch (error) { add('流式响应（可选）', 'warn', error.message); }
    finally { busy(false); }
};
el('export').onclick = () => {
    const data = {probe_version:1, tested_at:new Date().toISOString(), verdict:el('verdict').textContent, tests:rows};
    const url = URL.createObjectURL(new Blob([JSON.stringify(data,null,2)], {type:'application/json'}));
    const a = document.createElement('a'); a.href = url; a.download = 'php-relay-probe-report.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
};
</script></body></html>
