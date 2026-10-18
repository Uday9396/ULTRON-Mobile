package com.ultron.mobile
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
 private lateinit var web: WebView
 private var tts: TextToSpeech? = null
 private var recognizer: SpeechRecognizer? = null
 private val prefs by lazy { getSharedPreferences("ultron_mobile", Context.MODE_PRIVATE) }

 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(b: Bundle?) { super.onCreate(b); tts=TextToSpeech(this,this)
  web=WebView(this).apply { setBackgroundColor(android.graphics.Color.BLACK); settings.javaScriptEnabled=true; settings.domStorageEnabled=true; webViewClient=WebViewClient(); webChromeClient=WebChromeClient(); addJavascriptInterface(Bridge(),"UltronAndroid"); loadUrl("file:///android_asset/ultron.html") }; setContentView(web)
 }
 override fun onInit(status:Int){
  if(status==TextToSpeech.SUCCESS){
   tts?.language=Locale.US
   // ULTRON-inspired delivery: deeper, slower and controlled.
   // This is intentionally not an imitation of any specific actor.
   tts?.setPitch(0.72f)
   tts?.setSpeechRate(0.86f)
   val candidates=tts?.voices?.filter { v ->
    val l=v.locale
    l.language==Locale.ENGLISH.language && !v.isNetworkConnectionRequired
   }.orEmpty()
   // Prefer voices whose engine metadata suggests a male/deep profile.
   // If Android does not expose such a label, keep the engine's default English voice
   // rather than randomly selecting a high-pitched voice.
   val preferred=candidates.firstOrNull { v ->
    val n=v.name.lowercase(Locale.ROOT)
    n.contains("male") || n.contains("deep") || n.contains("low")
   }
   if(preferred!=null) tts?.voice=preferred
  }
 }
 private fun js(fn:String,v:String){ val q=JSONObject.quote(v); runOnUiThread{web.evaluateJavascript("$fn($q)",null)} }
 private fun listen(){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),40);return}
  recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this).also{sr->
   sr.setRecognitionListener(object:RecognitionListener{
    override fun onReadyForSpeech(p:Bundle?)=js("voiceState","LISTENING")
    override fun onResults(r:Bundle?){js("voiceResult",r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())}
    override fun onError(e:Int)=js("voiceState","VOICE ERROR")
    override fun onBeginningOfSpeech(){}; override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}; override fun onEndOfSpeech()=js("voiceState","PROCESSING"); override fun onPartialResults(p:Bundle?){}; override fun onEvent(t:Int,p:Bundle?){}
   })
   sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())})
  }
 }
 inner class Bridge{
  @JavascriptInterface fun startVoice()=runOnUiThread{listen()}
  @JavascriptInterface fun speak(text:String)=runOnUiThread{tts?.stop();tts?.setPitch(0.62f);tts?.setSpeechRate(0.82f);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"ultron")}
  @JavascriptInterface fun saveConnection(base:String,token:String){prefs.edit().putString("base",base.trim().trimEnd('/')).putString("token",token.trim()).apply();js("connectionState","SAVED")}
  @JavascriptInterface fun getBaseUrl():String=prefs.getString("base","")?:""
  @JavascriptInterface fun sendMessage(message:String){val base=prefs.getString("base","")?.trim()?.trimEnd('/').orEmpty();val token=prefs.getString("token","")?.trim().orEmpty();if(base.isBlank()){js("receiveError","Set the PC address first.");return}
   thread{try{val c=URL("$base/api/chat").openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=120000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(token.isNotBlank())c.setRequestProperty("X-ULTRON-APP-TOKEN",token);val body=JSONObject().put("message",message).toString();c.outputStream.use{it.write(body.toByteArray())};val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;val j=JSONObject(s.bufferedReader().use{it.readText()});if(c.responseCode in 200..299&&j.optBoolean("ok"))js("receiveUltron",j.optString("response")) else js("receiveError",j.optString("error","ULTRON core rejected the request."));c.disconnect()}catch(ex:Exception){js("receiveError","Connection failed: "+ex.javaClass.simpleName)}}
  }
 }
 override fun onDestroy(){recognizer?.destroy();tts?.stop();tts?.shutdown();web.destroy();super.onDestroy()}
}