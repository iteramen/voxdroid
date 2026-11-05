package io.github.iteramen.voxdroid.probe;
import android.app.*; import android.content.*; import android.net.Uri; import android.os.*;
import android.provider.Settings; import android.view.*; import android.widget.*;
import android.graphics.PixelFormat; import android.media.MediaMetadataRetriever;
import org.json.JSONObject; import java.io.*; import java.security.MessageDigest;
import java.util.concurrent.Executors;

public class ProbeActivity extends Activity {
 static volatile ProbeActivity active;
 public void onCreate(Bundle state) {
  super.onCreate(state); active=this;
  Intent i=getIntent();
  Uri uri=i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
  TextView t=new TextView(this); t.setPadding(32,64,32,32); t.setTextSize(18);
  setContentView(t);
  if(Intent.ACTION_SEND.equals(i.getAction()) && uri!=null) {
   if(i.getClipData()!=null && i.getClipData().getItemCount()!=1) {t.setText("Select one fixture."); return;}
   t.setText("Measuring selected fixture; this closes back to the source app.");
   // Plain string extra, no data/ClipData/grant flags: does not extend the Activity's URI grant.
   Intent work=new Intent(this,Worker.class);
   work.putExtra("uri",uri.toString()); work.putExtra("mime",i.getType()); work.putExtra("flags",i.getFlags());
   startService(work);
  } else t.setText("Voice handoff probe\n\nNo network, microphone or sending. Share one prepared audio fixture here. Initial read, then return and a second read after 2 seconds. Only hashes and metadata are retained. Close overlay before navigating.");
 }
 protected void onDestroy(){if(active==this)active=null;super.onDestroy();}
 public static class Worker extends Service {
  final Handler main=new Handler(Looper.getMainLooper());
  final java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
  View panel; WindowManager windows;
  public IBinder onBind(Intent i){return null;}
  public int onStartCommand(Intent i,int flags,int id) {
   if(i==null)return START_NOT_STICKY;
   Uri uri=Uri.parse(i.getStringExtra("uri")); String mime=i.getStringExtra("mime"); int originalFlags=i.getIntExtra("flags",0);
   executor.execute(()->{
    JSONObject r=new JSONObject();
    try {
     r.put("time_ms",System.currentTimeMillis()); r.put("authority",uri.getAuthority());
     r.put("mime",mime); r.put("intent_flags",originalFlags);
     r.put("resolver_mime",getContentResolver().getType(uri)); r.put("initial",measure(uri));
     MediaMetadataRetriever m=new MediaMetadataRetriever();
     try {m.setDataSource(this,uri);
      r.put("duration_ms",m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      r.put("media_mime",m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
      r.put("sample_rate",m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE));
     }catch(Exception e){r.put("media_error",e.getClass().getSimpleName());}
     finally{try{m.release();}catch(Exception ignored){}}
    }catch(Exception e){try{r.put("initial_error",e.getClass().getSimpleName());}catch(Exception ignored){}}
    main.post(()->{
     ProbeActivity a=active;if(a!=null)a.finish();
     main.postDelayed(()->executor.execute(()->{
      try{r.put("after_return_2s",measure(uri));}
      catch(Exception e){try{r.put("after_return_error",e.getClass().getSimpleName());}catch(Exception ignored){}}
      try(FileOutputStream out=openFileOutput("reports.jsonl",MODE_APPEND)){
       out.write((r.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }catch(Exception ignored){}
      main.post(()->show(r));
     }),2000);
    });
   });return START_NOT_STICKY;
  }
  JSONObject measure(Uri uri)throws Exception{
   MessageDigest d=MessageDigest.getInstance("SHA-256");long size=0,deadline=SystemClock.elapsedRealtime()+30000;
   byte[] b=new byte[65536];
   try(InputStream in=getContentResolver().openInputStream(uri)){
    if(in==null)throw new IOException();int n;
    while((n=in.read(b))!=-1){size+=n;if(size>32L*1024*1024||SystemClock.elapsedRealtime()>deadline)throw new IOException();d.update(b,0,n);}
   }
   StringBuilder h=new StringBuilder();for(byte x:d.digest())h.append(String.format(java.util.Locale.ROOT,"%02x",x&255));
   return new JSONObject().put("bytes",size).put("sha256",h.toString());
  }
  void show(JSONObject r){
   if(!Settings.canDrawOverlays(this)){stopSelf();return;} hide();
   windows=(WindowManager)getSystemService(WINDOW_SERVICE);
   LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,16,24,16);box.setBackgroundColor(0xff202124);
   TextView t=new TextView(this);t.setTextColor(-1);t.setTextSize(13);
   try{t.setText("VOICE HANDOFF PROBE — METADATA ONLY\n"+r.toString(2));}catch(Exception ignored){}
   box.addView(t);Button close=new Button(this);close.setText("Close probe");
   close.setOnClickListener(v->{hide();stopSelf();});box.addView(close);
   WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
   p.gravity=Gravity.TOP;p.y=96;panel=box;
   try{windows.addView(panel,panelParams(p));}catch(Exception e){panel=null;stopSelf();}
  }
  WindowManager.LayoutParams panelParams(WindowManager.LayoutParams p){return p;}
  void hide(){if(panel!=null&&windows!=null){windows.removeView(panel);panel=null;}}
  public void onDestroy(){hide();executor.shutdownNow();super.onDestroy();}
 }
}
