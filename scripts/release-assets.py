"""Portable build-time country conversion/font renaming, no network/runtime."""
import argparse,csv,gzip,hashlib,io,ipaddress,json,re
from pathlib import Path

def country(args):
    compressed=Path(args.input).read_bytes()
    if len(compressed)>40*1024*1024:raise ValueError('COUNTRY_INPUT_SIZE')
    with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
        plain=stream.read(40*1024*1024+1)
    if len(plain)>40*1024*1024:raise ValueError('COUNTRY_PLAIN_SIZE')
    if args.sha1 not in (hashlib.sha1(compressed).hexdigest(),hashlib.sha1(plain).hexdigest()):
        raise ValueError('COUNTRY_INPUT_DIGEST')
    groups={c:[] for c in ('RU','IR','CN')};count=0
    for row in csv.reader(io.StringIO(plain.decode('utf-8'))):
        if len(row)!=3 or not re.fullmatch('[A-Z]{2}',row[2]):raise ValueError('COUNTRY_ROW')
        a,b=ipaddress.ip_address(row[0]),ipaddress.ip_address(row[1])
        if a.version!=b.version or int(a)>int(b):raise ValueError('COUNTRY_RANGE')
        count+=1
        if count>2000000:raise ValueError('COUNTRY_RECORD_LIMIT')
        if row[2] in groups:groups[row[2]].extend(ipaddress.summarize_address_range(a,b))
    out=Path(args.output);out.mkdir();rows=[]
    for c,nets in groups.items():
        if not nets or not all(n.is_global for n in nets):raise ValueError('COUNTRY_PUBLIC_PREFIX')
        v4=list(ipaddress.collapse_addresses(n for n in nets if n.version==4))
        v6=list(ipaddress.collapse_addresses(n for n in nets if n.version==6))
        values={'txt':('\n'.join(map(str,v4))+'\n').encode(),
            'json':(json.dumps(dict(version=1,rules=[dict(ip_cidr=list(map(str,v4+v6)))]),separators=(',',':'))+'\n').encode()}
        for ext,data in values.items():
            name='geoip-'+c.lower()+'.'+ext
            with (out/name).open('xb') as s:s.write(data)
            rows.append(dict(name=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    print(json.dumps(dict(records=count,files=rows)))

def fonts(args):
    import fontTools
    from fontTools.ttLib import TTFont
    if fontTools.__version__!='4.64.0':raise ValueError('PINNED_FONTTOOLS_REQUIRED')
    expected={'regular':'2e58e578e7f3ac40fb9f8f9e36f3954a12ebcc34e2356ad1fa2dea00005550da',
        'medium':'4c5c3c2d172a884704998d9167365aaf2188c9cf63ed5b75b1ebb86c94f5404d',
        'semi_bold':'297ade9cd7f13c934f360f9d40b57608c0f8d00797d4251c0062ed846c9021a8'}
    out=Path(args.output);out.mkdir();rows=[]
    for style,digest in expected.items():
        name='ibm_plex_sans_'+style+'.ttf';source=Path(args.input)/name;data=source.read_bytes()
        if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('FONT_INPUT_DIGEST')
        weight={'regular':'Regular','medium':'Medium','semi_bold':'SemiBold'}[style]
        family='Apollo RGA Sans'
        values={1:family,2:weight,3:'ApolloRgaSans-3.201-Rga1-'+weight,4:family+' '+weight,
            6:'ApolloRgaSans-'+weight,16:family,17:weight,18:family+' '+weight,21:family,22:weight,25:'ApolloRgaSans'}
        with TTFont(io.BytesIO(data),recalcTimestamp=False) as f:
            for r in list(f['name'].names):
                if r.nameID in values:f['name'].setName(values[r.nameID],r.nameID,r.platformID,r.platEncID,r.langID)
            f.save(out/name,reorderTables=False)
        result=(out/name).read_bytes();rows.append(dict(name=name,bytes=len(result),sha256=hashlib.sha256(result).hexdigest()))
    print(json.dumps(dict(files=rows)))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    sub=parser.add_subparsers(dest='action',required=True)
    for action in ('country','fonts'):
        p=sub.add_parser(action);p.add_argument('--input',required=True);p.add_argument('--output',required=True)
        if action=='country':p.add_argument('--sha1',required=True)
    args=parser.parse_args();(country if args.action=='country' else fonts)(args)
