#!/usr/bin/env python3
from pathlib import Path
import subprocess,zipfile
r=Path(__file__).resolve().parent
t=Path("/opt/android-sdk/build-tools/36.0.0")
a=Path("/opt/android-sdk/platforms/android-36/android.jar")
o=r/"build"; o.mkdir(exist_ok=True)
c=o/"classes"; c.mkdir(exist_ok=True)
d=o/"dex"; d.mkdir(exist_ok=True)
def run(*args): subprocess.run([str(x) for x in args],check=True)
run("javac","-source","17","-target","17","-classpath",a,"-d",c,r/"ProbeActivity.java")
run(t/"d8","--lib",a,"--min-api","33","--output",d,*sorted(c.rglob("*.class")))
run(t/"aapt2","link","--manifest",r/"AndroidManifest.xml","-I",a,"-o",o/"unsigned.apk")
with zipfile.ZipFile(o/"unsigned.apk","a") as z:z.write(d/"classes.dex","classes.dex")
run(t/"zipalign","-f","4",o/"unsigned.apk",o/"aligned.apk")
k=o/"probe.keystore"
if not k.exists():run("keytool","-genkeypair","-keystore",k,"-storepass","android","-keypass","android","-alias","probe","-dname","CN=Voxdroid isolated probe","-keyalg","RSA","-validity","3650","-noprompt")
run(t/"apksigner","sign","--ks",k,"--ks-pass","pass:android","--key-pass","pass:android","--out",o/"voice-handoff-probe.apk",o/"aligned.apk")
run(t/"apksigner","verify","--verbose",o/"voice-handoff-probe.apk")
print(o/"voice-handoff-probe.apk")
