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
     try{r.put("decode",decode(uri));}catch(Exception e){r.put("decode_error",e.getClass().getSimpleName());}
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

  JSONObject decode(Uri uri)throws Exception{
   android.media.MediaExtractor extractor=new android.media.MediaExtractor();
   android.media.MediaCodec codec=null; boolean started=false;
   try{
    extractor.setDataSource(this,uri,null);
    if(extractor.getTrackCount()!=1)throw new IOException("single track required");
    android.media.MediaFormat format=extractor.getTrackFormat(0);
    String mime=format.getString(android.media.MediaFormat.KEY_MIME);
    if(!"audio/opus".equals(mime))throw new IOException("probe supports opus only");
    format.setInteger(android.media.MediaFormat.KEY_PCM_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT);
    extractor.selectTrack(0);
    codec=android.media.MediaCodec.createDecoderByType(mime);
    codec.configure(format,null,null,0);codec.start();started=true;
    JSONObject result=new JSONObject().put("track_mime",mime).put("decoder",codec.getName());
    android.media.MediaCodec.BufferInfo info=new android.media.MediaCodec.BufferInfo();
    boolean inputDone=false,outputDone=false;long totalBytes=0,deadline=SystemClock.elapsedRealtime()+30000;
    int rate=0,channels=0,encoding=0;
    while(!outputDone){
     if(SystemClock.elapsedRealtime()>deadline || Thread.currentThread().isInterrupted())throw new IOException("decode deadline");
     if(!inputDone){
      int index=codec.dequeueInputBuffer(10000);
      if(index>=0){
       java.nio.ByteBuffer input=codec.getInputBuffer(index);if(input==null)throw new IOException("no input buffer");
       input.clear();
       int size=extractor.readSampleData(input,0);
       if(size<0){codec.queueInputBuffer(index,0,0,0,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
       else{
        if(size>8*1024*1024 || size>input.capacity() || (extractor.getSampleFlags()&android.media.MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)
         throw new IOException("unsupported sample");
        codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();
       }
      }
     }
     int output=codec.dequeueOutputBuffer(info,10000);
     if(output==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
      android.media.MediaFormat actual=codec.getOutputFormat();
      int nextRate=actual.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
      int nextChannels=actual.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
      int nextEncoding=actual.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT);
      if(nextRate<8000||nextRate>48000||nextChannels<1||nextChannels>2||nextEncoding!=android.media.AudioFormat.ENCODING_PCM_16BIT)
       throw new IOException("unsupported PCM format");
      if(rate!=0 && (rate!=nextRate||channels!=nextChannels||encoding!=nextEncoding))throw new IOException("changing PCM format");
      rate=nextRate;channels=nextChannels;encoding=nextEncoding;
     }else if(output>=0){
      try{
       if((info.flags&android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0){
        java.nio.ByteBuffer pcm=codec.getOutputBuffer(output);
        if(rate==0||pcm==null||info.size>8*1024*1024||info.offset<0||(long)info.offset+info.size>pcm.capacity())
         throw new IOException("invalid PCM buffer");
        if(info.size%(channels*2)!=0)throw new IOException("unaligned PCM frames");
        totalBytes+=info.size;
        if(totalBytes>300L*rate*channels*2)throw new IOException("over five minutes");
        // Count and discard output. No PCM copy, playback, transcript, file, or network operation.
       }
       outputDone=(info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
      }finally{codec.releaseOutputBuffer(output,false);}
     }
    }
    if(totalBytes==0||rate==0)throw new IOException("empty PCM");
    long frames=totalBytes/(channels*2);
    return result.put("pcm_rate",rate).put("pcm_channels",channels).put("pcm_encoding",encoding)
     .put("pcm_bytes",totalBytes).put("pcm_frames",frames).put("decoded_duration_ms",frames*1000/rate).put("eos",true);
   }finally{
    if(codec!=null){if(started){try{codec.stop();}catch(Exception ignored){}}codec.release();}
    extractor.release();
   }
  }

  void show(JSONObject r){
   if(!Settings.canDrawOverlays(this)){stopSelf();return;} hide();
   windows=(WindowManager)getSystemService(WINDOW_SERVICE);
   LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,16,24,16);box.setBackgroundColor(0xff202124);
   TextView t=new TextView(this);t.setTextColor(-1);t.setTextSize(13);
   try{t.setText("VOICE HANDOFF PROBE V2 — METADATA ONLY\n"+r.toString(2));}catch(Exception ignored){}
   ScrollView scroll=new ScrollView(this);scroll.addView(t);box.addView(scroll,new LinearLayout.LayoutParams(-1,600));Button close=new Button(this);close.setText("Close probe");
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
