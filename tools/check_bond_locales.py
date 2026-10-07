"""Audit RideFlux string coverage, duplicate keys and positional format signatures."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/res'
PATTERN = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])')
def signature(text):
    automatic = 0
    result = Counter()
    for index, kind in PATTERN.findall(text):
        if kind == '%':
            continue
        if not index:
            automatic += 1
            index = str(automatic)
        result[(int(index), kind)] += 1
    return result

def read(folder):
    result = {}
    for path in folder.glob('*.xml'):
        for element in ET.parse(path).getroot():
            if element.tag not in ('string', 'plurals', 'string-array'):
                continue
            name = element.attrib['name']
            assert name not in result, f'{folder.name}: duplicate {name}'
            result[name] = element
    return result

base = read(ROOT / 'values')
translatable = {k: v for k, v in base.items() if v.attrib.get('translatable') != 'false'}
for folder in sorted(ROOT.glob('values-*')):
    localized = read(folder)
    if not localized:
        continue
    missing = set(translatable) - set(localized)
    assert not missing, f'{folder.name}: missing {sorted(missing)}'
    for key, original in translatable.items():
        translated = localized[key]
        assert original.tag == translated.tag, f'{folder.name}/{key}: resource type mismatch'
        if original.tag == 'plurals':
            quantities = [e.attrib.get('quantity') for e in translated]
            assert len(quantities) == len(set(quantities)), f'{folder.name}/{key}: duplicate quantity'
            assert 'other' in quantities, f'{folder.name}/{key}: missing other'
            assert set(quantities) <= {'zero', 'one', 'two', 'few', 'many', 'other'}, f'{folder.name}/{key}: invalid quantity'
            expected = signature(''.join(next(e for e in original if e.attrib['quantity'] == 'other').itertext()))
            for item in translated:
                assert signature(''.join(item.itertext())) == expected, f'{folder.name}/{key}: plural format mismatch'
        else:
            assert signature(''.join(original.itertext())) == signature(''.join(translated.itertext())), f'{folder.name}/{key}: format mismatch'
        if key.startswith('bond_'):
            assert ''.join(original.itertext()) != ''.join(translated.itertext()), f'{folder.name}/{key}: English duplicate'
    print(f'PASS {folder.name}: {len(translatable)} translated resources; bond strings translated; format signatures match')
print('PASS default resources: unique keys')
