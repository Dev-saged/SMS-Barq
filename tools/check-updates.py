# Usage: python3 -I tools/check-updates.py gradle/libs.versions.toml  (latest stable per library, from Maven)
import re,sys,urllib.request,xml.etree.ElementTree as ET
G="https://dl.google.com/dl/android/maven2/"; C="https://repo1.maven.org/maven2/"
mods={
 "agp":("com.android.tools.build:gradle",G),"kotlin":("org.jetbrains.kotlin:kotlin-gradle-plugin",C),
 "ksp":("com.google.devtools.ksp:symbol-processing-gradle-plugin",C),"hilt":("com.google.dagger:hilt-android",C),
 "androidxHilt":("androidx.hilt:hilt-work",G),"coreKtx":("androidx.core:core-ktx",G),
 "activityCompose":("androidx.activity:activity-compose",G),"lifecycle":("androidx.lifecycle:lifecycle-runtime-compose",G),
 "composeBom":("androidx.compose:compose-bom",G),"navigation":("androidx.navigation:navigation-compose",G),
 "room":("androidx.room:room-runtime",G),"paging":("androidx.paging:paging-compose",G),"work":("androidx.work:work-runtime-ktx",G),
 "datastore":("androidx.datastore:datastore-preferences",G),"glance":("androidx.glance:glance-appwidget",G),
 "coroutines":("org.jetbrains.kotlinx:kotlinx-coroutines-android",C),"serialization":("org.jetbrains.kotlinx:kotlinx-serialization-json",C),
 "junit":("junit:junit",C),"robolectric":("org.robolectric:robolectric",C),"biometric":("androidx.biometric:biometric",G),
 "fragment":("androidx.fragment:fragment-ktx",G)}
toml=open(sys.argv[1]).read()
cur=dict(re.findall(r'^(\w+) = "([^"]+)"',toml.split('[libraries]')[0],re.M))
def key(v): return [int(x) if x.isdigit() else 0 for x in re.split(r'[.\-]',re.sub(r'[^0-9.]','.',v.split('-')[0]))]
for k,(m,repo) in mods.items():
    g,a=m.split(':')
    try:
        x=urllib.request.urlopen(repo+g.replace('.','/')+'/'+a+'/maven-metadata.xml',timeout=20).read()
        vs=[v.text for v in ET.fromstring(x).iter('version')]
    except Exception as e:
        print(k,cur.get(k),'ERR',e); continue
    stable=[v for v in vs if re.fullmatch(r'\d+(\.\d+)*',v)]
    stable.sort(key=key)
    c=cur.get(k,'?'); major=c.split('.')[0]
    same=[v for v in stable if v.split('.')[0]==major]
    print(f"{k:16} cur={c:10} latestStable={stable[-1] if stable else '-':12} sameMajor={same[-1] if same else '-'}")
