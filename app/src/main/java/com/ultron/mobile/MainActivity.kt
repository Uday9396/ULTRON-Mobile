package com.ultron.mobile
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.Inet4Address
import android.net.ConnectivityManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
 private lateinit var web: WebView
 private var tts: TextToSpeech? = null
 private var recognizer: SpeechRecognizer? = null
 private var torchOn = false
 private var liveVoiceMode = false
 private val prefs by lazy { getSharedPreferences("ultron_mobile", Context.MODE_PRIVATE) }
 private val defaultPcBase = "http://192.168.0.104:5000"

 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(b: Bundle?) { super.onCreate(b)
  tts=TextToSpeech(this,this)
  tts?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
   override fun onStart(id:String?){}
   override fun onDone(id:String?){if(id=="ultron_home_voice")js("homeVoiceDone","")}
   @Deprecated("Deprecated in Java") override fun onError(id:String?){if(id=="ultron_home_voice")js("homeVoiceDone","")}
  })
  web=WebView(this).apply { setBackgroundColor(android.graphics.Color.BLACK); settings.javaScriptEnabled=true; settings.domStorageEnabled=true; webViewClient=WebViewClient(); webChromeClient=WebChromeClient(); addJavascriptInterface(Bridge(),"UltronAndroid"); loadUrl("file:///android_asset/ultron.html") }; setContentView(web)
 }
 private fun js(fn:String,v:String){ val q=JSONObject.quote(v); runOnUiThread{web.evaluateJavascript("$fn($q)",null)} }
 private fun tryChat(base:String,token:String,message:String):String?{
  return try{
   val c=URL("${base.trimEnd('/')}/api/chat").openConnection() as HttpURLConnection
   c.requestMethod="POST";c.connectTimeout=5000;c.readTimeout=120000;c.doOutput=true
   c.setRequestProperty("Content-Type","application/json")
   if(token.isNotBlank())c.setRequestProperty("X-ULTRON-APP-TOKEN",token)
   c.outputStream.use{it.write(JSONObject().put("message",message).toString().toByteArray())}
   val code=c.responseCode
   val s=if(code in 200..299)c.inputStream else c.errorStream
   val raw=s?.bufferedReader()?.use{it.readText()}.orEmpty()
   c.disconnect()
   if(code in 200..299){
    val j=JSONObject(raw)
    if(j.optBoolean("ok")){
     j.optString("response")
    }else null
   }else null
  }catch(_:Exception){null}
 }
 private fun currentIpv4Prefix():String?{
  return try{
   val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
   val network=cm.activeNetwork ?: return null
   val address=cm.getLinkProperties(network)?.linkAddresses
    ?.mapNotNull{it.address as? Inet4Address}
    ?.firstOrNull{!it.isLoopbackAddress}?.hostAddress ?: return null
   address.substringBeforeLast('.', "")
  }catch(_:Exception){null}
 }
 private fun looksLikeUltron(base:String):Boolean{
  return try{
   val c=URL(base.trimEnd('/') + "/").openConnection() as HttpURLConnection
   c.requestMethod="GET"; c.connectTimeout=350; c.readTimeout=700
   val code=c.responseCode
   val raw=if(code in 200..299)c.inputStream.bufferedReader().use{it.readText().take(12000)} else ""
   c.disconnect()
   code in 200..299 && raw.contains("ULTRON", ignoreCase=true)
  }catch(_:Exception){false}
 }
 private fun discoverCore():String?{
  val saved=prefs.getString("base","")?.trim()?.trimEnd('/').orEmpty()
  val quick=listOf(saved,defaultPcBase).filter{it.isNotBlank()}.distinct()
  quick.firstOrNull{looksLikeUltron(it)}?.let{return it}
  val prefix=currentIpv4Prefix() ?: return null
  val found=AtomicReference<String?>(null)
  val pool=Executors.newFixedThreadPool(24)
  for(i in 1..254){
   pool.submit{
    if(found.get()!=null)return@submit
    val candidate="http://$prefix.$i:5000"
    if(looksLikeUltron(candidate))found.compareAndSet(null,candidate)
   }
  }
  pool.shutdown()
  pool.awaitTermination(5,TimeUnit.SECONDS)
  pool.shutdownNow()
  return found.get()?.also{
   prefs.edit().putString("base",it).apply()
   js("connectionState","AUTO-CONNECTED: $it")
  }
 }
 private fun listen(live:Boolean=false){
  liveVoiceMode=live
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),40);return}
  recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this).also{sr->
   sr.setRecognitionListener(object:RecognitionListener{
    override fun onReadyForSpeech(p:Bundle?)=js("voiceState","LISTENING")
    override fun onResults(r:Bundle?){val heard=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if(liveVoiceMode)handleLiveVoice(heard) else js("voiceResult",heard)}
    override fun onError(e:Int)=js("voiceState","VOICE ERROR")
    override fun onBeginningOfSpeech(){}; override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}; override fun onEndOfSpeech()=js("voiceState","PROCESSING"); override fun onPartialResults(p:Bundle?){}; override fun onEvent(t:Int,p:Bundle?){}
   })
   sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())})
  }
 }
 private fun handleLiveVoice(message:String){
  if(message.isBlank()){js("liveVoiceState","LISTENING");runOnUiThread{listen(true)};return}
  js("liveVoiceHeard",message); js("liveVoiceState","THINKING")
  val base=prefs.getString("base",defaultPcBase)?.trim()?.trimEnd('/').orEmpty()
  val token=prefs.getString("token","")?.trim().orEmpty()
  thread{
   var answer=tryChat(base,token,message)
   if(answer==null){
    val discovered=discoverCore()
    if(discovered!=null)answer=tryChat(discovered,token,message)
   }
   if(answer!=null){
    js("liveVoiceReply",answer)
    runOnUiThread{
     tts?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
      override fun onStart(id:String?){js("liveVoiceState","SPEAKING")}
      override fun onDone(id:String?){if(liveVoiceMode){js("liveVoiceState","LISTENING");runOnUiThread{listen(true)}}}
      @Deprecated("Deprecated in Java") override fun onError(id:String?){if(liveVoiceMode){js("liveVoiceState","LISTENING");runOnUiThread{listen(true)}}}
     })
     tts?.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"ultron_live_voice")
    }
   }else{
    js("liveVoiceState","CORE UNAVAILABLE")
   }
  }
 }
 private fun openCamera(){
  try{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}catch(_:Exception){js("cameraState","CAMERA UNAVAILABLE")}
 }
 private fun toggleTorch(){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
   ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),41); return
  }
  try{
   val manager=getSystemService(Context.CAMERA_SERVICE) as CameraManager
   val cameraId=manager.cameraIdList.firstOrNull{id->
    val c=manager.getCameraCharacteristics(id)
    c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true && c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK
   } ?: run{js("flashlightState","UNAVAILABLE");return}
   torchOn=!torchOn
   manager.setTorchMode(cameraId,torchOn)
   js("flashlightState",if(torchOn)"ON" else "OFF")
  }catch(_:Exception){torchOn=false;js("flashlightState","UNAVAILABLE")}
 }
 inner class Bridge{
  @JavascriptInterface fun startVoice()=runOnUiThread{listen(false)}
  @JavascriptInterface fun startLiveVoice()=runOnUiThread{liveVoiceMode=true;tts?.stop();listen(true)}
  @JavascriptInterface fun stopLiveVoice()=runOnUiThread{liveVoiceMode=false;recognizer?.cancel();tts?.stop();js("liveVoiceState","READY")}
  @JavascriptInterface fun openCamera()=runOnUiThread{this@MainActivity.openCamera()}
  @JavascriptInterface fun toggleFlashlight()=runOnUiThread{toggleTorch()}
  @JavascriptInterface fun speak(text:String){
   if(text.isBlank()) return
   runOnUiThread{tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"ultron_reply")}
  }
  @JavascriptInterface fun stopSpeaking()=runOnUiThread{tts?.stop()}
  @JavascriptInterface fun saveConnection(base:String,token:String){prefs.edit().putString("base",base.trim().trimEnd('/')).putString("token",token.trim()).apply();js("connectionState","SAVED")}
  @JavascriptInterface fun getBaseUrl():String=prefs.getString("base",defaultPcBase)?:defaultPcBase
  @JavascriptInterface fun sendHomeVoiceMessage(message:String){
   val base=prefs.getString("base",defaultPcBase)?.trim()?.trimEnd('/').orEmpty()
   val token=prefs.getString("token","")?.trim().orEmpty()
   js("homeVoiceThinking","")
   thread{
    var answer=tryChat(base,token,message)
    if(answer==null){
     val discovered=discoverCore()
     if(discovered!=null)answer=tryChat(discovered,token,message)
    }
    if(answer!=null)js("homeVoiceReply",answer) else js("homeVoiceError","ULTRON core unavailable.")
   }
  }
  @JavascriptInterface fun speakHomeVoice(text:String){
   if(text.isBlank()){js("homeVoiceDone","");return}
   runOnUiThread{tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"ultron_home_voice")}
  }
  @JavascriptInterface fun sendMessage(message:String){
   val base=prefs.getString("base",defaultPcBase)?.trim()?.trimEnd('/').orEmpty()
   val token=prefs.getString("token","")?.trim().orEmpty()
   thread{
    var activeBase=base
    var answer=tryChat(activeBase,token,message)
    if(answer==null){
     val discovered=discoverCore()
     if(discovered!=null){
      activeBase=discovered
      answer=tryChat(activeBase,token,message)
     }
    }
    if(answer!=null)js("receiveUltron",answer) else js("receiveError","ULTRON core unavailable. Make sure the PC core is running and both devices are on the same Wi-Fi.")
   }
  } }
 override fun onInit(status:Int){
  if(status==TextToSpeech.SUCCESS){
   tts?.language=Locale.US
   tts?.setPitch(0.72f)
   tts?.setSpeechRate(0.92f)
  }
 }
 override fun onDestroy(){recognizer?.destroy();tts?.stop();tts?.shutdown();if(torchOn){try{toggleTorch()}catch(_:Exception){}};web.destroy();super.onDestroy()}
}