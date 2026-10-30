"""Temporary, anonymous streaming availability probe; never prints stream URLs."""
import json
import urllib.error
import urllib.parse
import urllib.request

MUSIC_VERSION = '1.20260928.13.00'
VISION_UA = 'com.google.visionos.youtube/1.02 (RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X)'
SAFARI_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'


def send(endpoint, client, name, ua, extra=None, origin='https://www.youtube.com'):
    body = {'context': {'client': client}, **(extra or {})}
    req = urllib.request.Request(origin + '/youtubei/v1/' + endpoint + '?prettyPrint=false',
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json; charset=UTF-8',
        'User-Agent': ua, 'Origin': origin, 'Referer': origin + '/', 'X-YouTube-Client-Name': str(name),
        'X-YouTube-Client-Version': client['clientVersion'], 'X-Goog-Api-Format-Version': '2'})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return json.load(res)
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        return {'_error': str(e)}


def find_ids(node, out):
    if len(out) >= 2:
        return
    if isinstance(node, dict):
        item = node.get('musicResponsiveListItemRenderer')
        if item:
            video = item.get('playlistItemData', {}).get('videoId', '')
            if len(video) == 11 and video not in out:
                out.append(video)
            return
        for value in node.values(): find_ids(value, out)
    elif isinstance(node, list):
        for value in node: find_ids(value, out)


search_client = {'clientName': 'WEB_REMIX', 'clientVersion': MUSIC_VERSION, 'hl': 'en', 'gl': 'US'}
search = send('search', search_client, 67, 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36',
              {'query': 'popular music', 'params': 'EgWKAQIIAWoMEA4QChADEAQQCRAF'}, 'https://music.youtube.com')
ids = []
find_ids(search, ids)
print('search results used:', len(ids), 'error:', search.get('_error', 'none'))
if not ids:
    ids = ['dQw4w9WgXcQ']

candidates = [
    ('vision-current', 101, VISION_UA, {'clientName':'VISIONOS','clientVersion':'1.02','clientScreen':'WATCH','platform':'MOBILE','deviceMake':'Apple','deviceModel':'RealityDevice14,1','osName':'visionOS','osVersion':'25.6.0.23O471'}),
    ('vision-updated', 101, SAFARI_UA, {'clientName':'VISIONOS','clientVersion':'1.02','deviceMake':'Apple','deviceModel':'RealityDevice17,1','osName':'visionOS','osVersion':'26.5.23O471','userAgent':SAFARI_UA}),
    ('android-vr', 28, 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip', {'clientName':'ANDROID_VR','clientVersion':'1.65.10','deviceMake':'Oculus','deviceModel':'Quest 3','androidSdkVersion':32,'osName':'Android','osVersion':'12L'}),
    ('ios', 5, 'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)', {'clientName':'IOS','clientVersion':'21.26.4','deviceMake':'Apple','deviceModel':'iPhone16,2','osName':'iPhone','osVersion':'18.3.2.22D82'}),
]

for label, name, ua, client in candidates:
    client.update({'hl':'en','gl':'US','utcOffsetMinutes':0})
    visitor = send('visitor_id', client, name, ua).get('responseContext', {}).get('visitorData', '')
    if visitor:
        client['visitorData'] = visitor
    print(label, 'visitor:', bool(visitor))
    for video in ids:
        response = send('player', client, name, ua, {'videoId':video,'contentCheckOk':True,'racyCheckOk':True})
        status = response.get('playabilityStatus', {})
        formats = response.get('streamingData', {}).get('adaptiveFormats', [])
        direct = [x for x in formats if x.get('mimeType', '').startswith('audio/mp4') and x.get('url') and not x.get('signatureCipher') and not x.get('cipher') and not x.get('drmFamilies')]
        result = 'status=' + status.get('status', 'unknown') + ' reason=' + repr(status.get('reason', response.get('_error', ''))[:220]) + ' direct-audio=' + str(len(direct))
        if direct:
            url = direct[0]['url']
            parsed = urllib.parse.urlparse(url)
            if parsed.scheme == 'https' and (parsed.hostname == 'googlevideo.com' or (parsed.hostname or '').endswith('.googlevideo.com')):
                request = urllib.request.Request(url, headers={'User-Agent':ua, 'Range':'bytes=0-1023'})
                try:
                    with urllib.request.urlopen(request, timeout=20) as stream:
                        result += ' fetch-http=' + str(stream.status) + ' bytes=' + str(len(stream.read(1024)))
                except urllib.error.URLError as e:
                    result += ' fetch-error=' + str(e)
        print(' ', result)
