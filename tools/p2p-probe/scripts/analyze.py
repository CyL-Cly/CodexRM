"""Summarize paired rounds without publishing addresses or per-run secrets."""
import argparse, collections, hashlib, json, math, pathlib

p = argparse.ArgumentParser()
p.add_argument('directory', type=pathlib.Path)
p.add_argument('--expected', type=int)
a = p.parse_args()
config = json.loads((a.directory / 'phone-config.json').read_text(encoding='utf-8-sig'))
expected = a.expected or config['rounds']
rows = [json.loads(line) for line in (a.directory / 'rounds.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
paired = collections.defaultdict(dict)
for row in rows:
    if row['role'] in paired[row['round']]:
        raise RuntimeError(f"Duplicate role in round {row['round']}")
    paired[row['round']][row['role']] = row

def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    low, high = math.floor(position), math.ceil(position)
    return round(values[low] + (values[high] - values[low]) * (position - low), 2)

def timing(values):
    return dict(p50=percentile(values, .5), p90=percentile(values, .9), p95=percentile(values, .95), maximum=max(values, default=None))

def wilson(k, n):
    if not n:
        return [None, None]
    z = 1.959963984540054
    proportion = k / n
    denominator = 1 + z*z/n
    middle = (proportion + z*z/(2*n)) / denominator
    spread = z * math.sqrt(proportion*(1-proportion)/n + z*z/(4*n*n)) / denominator
    return [round(middle-spread, 6), round(middle+spread, 6)]

successes, failures, missing = [], [], []
for n in range(1, expected + 1):
    pair = paired[n]
    if set(pair) != {'agent', 'phone'}:
        missing.append(n)
        continue
    if all(all(record.get(key) is True for key in ('success', 'udpHit', 'quic', 'rpc')) and record.get('echoBytes') == 32768 and record.get('path') == 'direct-ipv4' for record in pair.values()):
        successes.append(n)
    else:
        failures.append(dict(round=n, reasons={role: record.get('error', 'incomplete protocol') for role, record in pair.items() if not record['success']}))

agents = [pair['agent'] for pair in paired.values() if 'agent' in pair]
phones = [pair['phone'] for pair in paired.values() if 'phone' in pair]
phone_different, phone_stable, agent_eim, agent_filtering = 0, 0, 0, 0
phone_observed_ports = []
for phone in phones:
    mappings = [m for m in phone['mappings'] if m.get('mapped') and not m.get('changePort')]
    if len({m['mapped'] for m in mappings}) > 1:
        phone_different += 1
    if len(mappings) >= 3 and mappings[0]['server'] == mappings[-1]['server'] and mappings[0]['mapped'] == mappings[-1]['mapped']:
        phone_stable += 1
    phone_observed_ports.extend(int(m['mapped'].rsplit(':', 1)[1]) for m in mappings)
for agent in agents:
    mappings = [m for m in agent['mappings'] if m.get('mapped') and not m.get('changePort')]
    if len(mappings) >= 3 and len({m['mapped'] for m in mappings}) == 1:
        agent_eim += 1
    if any(m.get('changePort') and 'timeout' in m.get('error', '') for m in agent['mappings']):
        agent_filtering += 1

count = len(successes)
summary = {
    'expectedRounds': expected,
    'completePairs': len(successes) + len(failures),
    'successfulDirectRounds': count,
    'observedSuccessRate': count / expected,
    'nominalWilson95': wilson(count, expected),
    'passesGreaterThan50Percent': not missing and count / expected > .5,
    'wilsonLowerGreaterThan50Percent': not missing and wilson(count, expected)[0] > .5,
    'missingRounds': missing,
    'failures': failures,
    'phoneParameters': {k: config[k] for k in ('rounds', 'sockets', 'ports', 'prediction', 'timeoutSeconds')},
    'agentTotalElapsedMs': timing([r['elapsedMs'] for r in agents]),
    'successfulAgentTotalElapsedMs': timing([r['elapsedMs'] for r in agents if r['success']]),
    'agentPunchMs': timing([r['punchMs'] for r in agents if r['udpHit']]),
    'phoneTotalElapsedMs': timing([r['elapsedMs'] for r in phones]),
    'hitPhases': dict(collections.Counter(r.get('hitPhase', 'none') for r in agents)),
    'phoneRoundsWithDestinationDependentMapping': phone_different,
    'phoneRoundsWithStableRepeatedDestination': phone_stable,
    'agentRoundsWithStableMappingAcrossObservedTargets': agent_eim,
    'agentRoundsWithAlternatePortFiltered': agent_filtering,
    'observedPhonePortRangeWidth': max(phone_observed_ports) - min(phone_observed_ports) + 1 if phone_observed_ports else 0,
    'probeTxMaximum': {role: max((r['probeTx'] for r in records), default=0) for role, records in (('agent', agents), ('phone', phones))},
    'batchesOf50': [dict(first=start, last=min(start+49, expected), successes=sum(start <= n <= min(start+49, expected) for n in successes)) for start in range(1, expected+1, 50)],
    'businessPath': 'IPv4 UDP / pinned mutual TLS QUIC / 32KiB hash checked upload and reverse RPC; no relay',
    'signaling': 'USB adb reverse to desktop loopback HTTP; not production RM signaling',
    'limitations': ['One phone, carrier, location and network epoch', 'Adjacent rounds can be correlated; confidence interval is nominal', 'This is an independent PoC, not the production Agent/client transport'],
    'rawEvidenceSHA256': hashlib.sha256((a.directory / 'rounds.jsonl').read_bytes()).hexdigest(),
}
out = a.directory / 'summary.json'
out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
