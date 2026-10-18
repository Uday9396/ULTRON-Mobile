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
import android.media.MediaPlayer
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
 private lateinit var web: WebView
 private var player: MediaPlayer? = null
 private var recognizer: SpeechRecognizer? = null
 private var torchOn = false
 private val prefs by lazy { getSharedPreferences("ultron_mobile", Context.MODE_PRIVATE) }
 private val defaultPcBase = "http://10.174.189.167:5000"

 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(b: Bundle?) { super.onCreate(b)
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
  @JavascriptInterface fun startVoice()=runOnUiThread{listen()}
  @JavascriptInterface fun openCamera()=runOnUiThread{this@MainActivity.openCamera()}
  @JavascriptInterface fun toggleFlashlight()=runOnUiThread{toggleTorch()}
  @JavascriptInterface fun speak(text:String){
   val base=prefs.getString("base",defaultPcBase)?.trim()?.trimEnd('/').orEmpty()
   val token=prefs.getString("token","")?.trim().orEmpty()
   if(base.isBlank()||text.isBlank()) return
   runOnUiThread{
    try{
     player?.stop(); player?.release()
     val encoded=java.net.URLEncoder.encode(text,"UTF-8")
     val url="$base/api/tts?text=$encoded"
     player=MediaPlayer().apply{
      setDataSource(this@MainActivity, android.net.Uri.parse(url), mapOf("X-ULTRON-APP-TOKEN" to token))
      setOnPreparedListener{it.start()}
      setOnCompletionListener{it.release(); if(player===it) player=null}
      setOnErrorListener{mp,_,_->mp.release(); if(player===mp) player=null; true}
      prepareAsync()
     }
    }catch(_:Exception){}
   }
  }
  @JavascriptInterface fun saveConnection(base:String,token:String){prefs.edit().putString("base",base.trim().trimEnd('/')).putString("token",token.trim()).apply();js("connectionState","SAVED")}
  @JavascriptInterface fun getBaseUrl():String=prefs.getString("base",defaultPcBase)?:defaultPcBase
  @JavascriptInterface fun sendMessage(message:String){
   val base=prefs.getString("base",defaultPcBase)?.trim()?.trimEnd('/').orEmpty()
   val token=prefs.getString("token","")?.trim().orEmpty()
   thread{
    val answer=tryChat(base,token,message)
    if(answer!=null)js("receiveUltron",answer) else js("receiveError","ULTRON core unavailable.")
   }
  } }
 override fun onDestroy(){recognizer?.destroy();player?.stop();player?.release();if(torchOn){try{toggleTorch()}catch(_:Exception){}};web.destroy();super.onDestroy()}
}