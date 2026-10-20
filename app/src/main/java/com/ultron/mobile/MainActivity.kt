package com.ultron.mobile
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.content.ActivityNotFoundException
import android.net.Uri
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
   val mobileMessage = "MOBILE OWNER MODE: The speaker is your configured owner. Address the owner as Master exactly as in Discord/desktop owner mode. Never roast, mock, insult, criticize, or act arrogant toward Master. Reply short and precise: normally 1-2 short sentences unless Master explicitly asks for detail. USER MESSAGE: $message"
   c.outputStream.use{it.write(JSONObject().put("message",mobileMessage).toString().toByteArray())}
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
 private fun listen(){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),40);return}
  recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this).also{sr->
   sr.setRecognitionListener(object:RecognitionListener{
    override fun onReadyForSpeech(p:Bundle?)=js("voiceState","LISTENING")
    override fun onResults(r:Bundle?){
      val heard=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
      val callPattern=Regex("(?i)^(?:(?:hey\\s+)?ultron[,:;.!?\\-]*\\s*)?(?:please\\s+)?(?:can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+)?(?:call|dial|phone|ring)\\s+(?:my\\s+)?(.+?)[.!?]*$")
      val callCandidates=heard.mapNotNull{candidate->
       callPattern.matchEntire(candidate)?.let{candidate to it.groupValues[1].trim()}
      }
      val chosen=if(callCandidates.isNotEmpty() && ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED){
       callCandidates.maxByOrNull{(_,name)->contactMatchScore(name)}?.first ?: callCandidates.first().first
      }else callCandidates.firstOrNull()?.first ?: heard.firstOrNull().orEmpty()
      js("voiceResult",chosen)
     }
    override fun onError(e:Int)=js("voiceState","VOICE ERROR")
    override fun onBeginningOfSpeech(){}; override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}; override fun onEndOfSpeech()=js("voiceState","PROCESSING"); override fun onPartialResults(p:Bundle?){}; override fun onEvent(t:Int,p:Bundle?){}
   })
   sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
     putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
     putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())
     putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5)
    })
  }
 }
 private fun openApplication(appName:String):String{
  val name=appName.trim()
  if(name.isBlank())return "Tell me which application to open."
  val knownPackages=mapOf(
   "youtube" to "com.google.android.youtube",
   "chrome" to "com.android.chrome",
   "whatsapp" to "com.whatsapp",
   "instagram" to "com.instagram.android",
   "facebook" to "com.facebook.katana",
   "spotify" to "com.spotify.music",
   "gmail" to "com.google.android.gm",
   "maps" to "com.google.android.apps.maps",
   "google maps" to "com.google.android.apps.maps",
   "play store" to "com.android.vending",
   "settings" to "com.android.settings"
  )
  val key=name.lowercase(Locale.ROOT)
  val known=knownPackages[key]?.let{packageName->packageManager.getLaunchIntentForPackage(packageName)}
  if(known!=null){
   runOnUiThread{startActivity(known)}
   return "Opening $name."
  }
  val launcherIntent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val apps=packageManager.queryIntentActivities(launcherIntent,PackageManager.MATCH_ALL)
  val exact=apps.firstOrNull{it.loadLabel(packageManager).toString().equals(name,ignoreCase=true)}
  val fuzzy=exact ?: apps.firstOrNull{it.loadLabel(packageManager).toString().contains(name,ignoreCase=true)}
  val target=fuzzy?.activityInfo
  return if(target!=null){
   val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    .setClassName(target.packageName,target.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   runOnUiThread{startActivity(intent)}
   "Opening "+fuzzy.loadLabel(packageManager).toString()+"."
  }else "I couldn't find an installed application named $name."
 }
 private fun searchWeb(query:String):String{
  val q=query.trim()
  if(q.isBlank())return "Tell me what to search for."
  return try{
   val intent=Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(q)))
   runOnUiThread{startActivity(intent)}
   "Searching for $q."
  }catch(_:ActivityNotFoundException){"I couldn't open a browser."}
 }
 private fun contactKey(value:String):String{
  return value.lowercase(Locale.ROOT)
   .replace(Regex("[^a-z0-9 ]")," ")
   .replace("oo","u")
   .replace("ou","u")
   .replace(Regex("\\s+")," ")
   .trim()
 }
 private fun contactMatches(spoken:String,name:String):Boolean{
  val q=contactKey(spoken)
  val full=contactKey(name)
  if(q.isBlank()||full.isBlank())return false
  if(full==q)return true
  val qWords=q.split(" ").filter{it.isNotBlank()}
  val nameWords=full.split(" ").filter{it.isNotBlank()}
  return qWords.all{qWord->nameWords.any{nameWord->nameWord==qWord || nameWord.startsWith(qWord)}}
 }
 private fun contactMatchScore(target:String):Int{
  val who=target.trim()
  if(who.isBlank())return 0
  var score=0
  contentResolver.query(
   ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
   arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),null,null,null
  )?.use{cur->
   while(cur.moveToNext()){
    val name=cur.getString(0)?.trim().orEmpty()
    if(contactMatches(who,name)){
     val candidate=if(contactKey(name)==contactKey(who))1000 else 100
     if(candidate>score)score=candidate
    }
   }
  }
  return score
 }
 private fun callContactOrNumber(target:String):String{
  val who=target.trim()
  if(who.isBlank())return "Tell me who to call."
  val digits=who.filter{it.isDigit() || it=='+'}
  var number=if(digits.count{it.isDigit()}>=5)digits else ""
  if(number.isBlank() && ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED){
   val projection=arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
   val matches=mutableListOf<Pair<String,String>>()
   contentResolver.query(
    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,projection,null,null,
    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE NOCASE ASC"
   )?.use{cur->
    while(cur.moveToNext()){
     val name=cur.getString(1)?.trim().orEmpty()
     val phone=cur.getString(0).orEmpty()
     if(contactMatches(who,name))matches.add(name to phone)
    }
   }
   val unique=matches.distinctBy{contactKey(it.first)}
   if(unique.size==1)number=unique.first().second
   else if(unique.size>1){
    runOnUiThread{
     val labels=unique.map{it.first}.toTypedArray()
     androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle("Choose contact")
      .setItems(labels){_,which->
       val selected=unique[which]
       try{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(selected.second))))}
       catch(_:Exception){}
      }
      .setNegativeButton("Cancel",null)
      .show()
    }
    return "I found ${unique.size} contacts matching $who. Choose the contact on screen."
   }
  }
  if(number.isBlank()){
   if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
    ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.READ_CONTACTS),42)
    return "Allow Contacts permission, then say call $who again."
   }
   return "I couldn't find $who in your contacts."
  }
  return try{
   runOnUiThread{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))))}
   "Opening the dialer for $who."
  }catch(_:Exception){"I couldn't open the dialer."}
 }
 private fun setAlarm(hour:Int,minute:Int,label:String="ULTRON"):String{
  return try{
   val i=Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute).putExtra(AlarmClock.EXTRA_MESSAGE,label)
   runOnUiThread{startActivity(i)}
   String.format(Locale.US,"Setting alarm for %d:%02d.",hour,minute)
  }catch(_:Exception){"I couldn't open the alarm app."}
 }
 private fun setTimer(seconds:Int,label:String="ULTRON"):String{
  return try{
   val i=Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,seconds).putExtra(AlarmClock.EXTRA_MESSAGE,label)
   runOnUiThread{startActivity(i)}
   "Setting timer."
  }catch(_:Exception){"I couldn't open the timer."}
 }
 private fun startNavigation(destination:String,origin:String?=null):String{
  val to=destination.trim()
  val from=origin?.trim().orEmpty()
  if(to.isBlank())return "Tell me where you want to navigate."
  return try{
   val navigationUrl="google.navigation:q="+Uri.encode(to)+"&mode=d"
   val directionsUrl=buildString{
    append("https://www.google.com/maps/dir/?api=1")
    if(from.isNotBlank())append("&origin=").append(Uri.encode(from))
    append("&destination=").append(Uri.encode(to))
    append("&travelmode=driving")
   }
   val targetUrl=if(from.isBlank())navigationUrl else directionsUrl
   val maps=Intent(Intent.ACTION_VIEW,Uri.parse(targetUrl)).apply{
    setPackage("com.google.android.apps.maps")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   }
   runOnUiThread{
    try{startActivity(maps)}
    catch(_:ActivityNotFoundException){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(directionsUrl)))}
   }
   if(from.isBlank())"Starting navigation to $to." else "Opening Maps with directions from $from to $to."
  }catch(_:Exception){"I couldn't open navigation."}
 }
 private fun tryLocalCommand(message:String):String?{
  val t=message.trim()
  val route=Regex("(?i)^(?:(?:hey\\s+)?ultron[,:;.!?\\-]*\\s*)?(?:please\\s+)?(?:(?:start|begin)\\s+)?(?:navigation|navigate|directions?)\\s+from\\s+(.+?)\\s+to\\s+(.+?)[.!?]*$").matchEntire(t)
  if(route!=null){
   val origin=route.groupValues[1].trim()
   val actualOrigin=if(origin.equals("my location",true)||origin.equals("current location",true)||origin.equals("here",true))null else origin
   return startNavigation(route.groupValues[2].trim(),actualOrigin)
  }
  val navTo=Regex("(?i)^(?:(?:hey\\s+)?ultron[,:;.!?\\-]*\\s*)?(?:please\\s+)?(?:(?:start|begin)\\s+)?(?:navigation|navigate|directions?)\\s+(?:to\\s+)(.+?)[.!?]*$").matchEntire(t)
  if(navTo!=null)return startNavigation(navTo.groupValues[1].trim())
  val call=Regex("(?i)^(?:(?:hey\\s+)?ultron[,:;.!?\\-]*\\s*)?(?:please\\s+)?(?:can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+)?(?:call|dial|phone|ring)\\s+(?:my\\s+)?(.+?)[.!?]*$").matchEntire(t)
  if(call!=null)return callContactOrNumber(call.groupValues[1].trim())
  val timer=Regex("(?i)^(?:please\\s+)?(?:set\\s+)?(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").matchEntire(t)
  if(timer!=null){
   val n=timer.groupValues[1].toInt()
   val unit=timer.groupValues[2].lowercase(Locale.ROOT)
   val seconds=when{unit.startsWith("h")->n*3600;unit.startsWith("m")->n*60;else->n}
   return setTimer(seconds)
  }
  val alarm=Regex("(?i)^(?:please\\s+)?(?:set\\s+)?(?:an?\\s+)?alarm\\s+(?:for\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").matchEntire(t)
  if(alarm!=null){
   var h=alarm.groupValues[1].toInt(); val min=alarm.groupValues[2].toIntOrNull()?:0; val ap=alarm.groupValues[3].lowercase(Locale.ROOT)
   if(ap=="pm" && h<12)h+=12
   if(ap=="am" && h==12)h=0
   if(h in 0..23 && min in 0..59)return setAlarm(h,min)
  }
  val open=Regex("(?i)^(?:please\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(?:app(?:lication)?\\s+)?(.+)$").matchEntire(t)
  if(open!=null)return openApplication(open.groupValues[1])
  val search=Regex("(?i)^(?:please\\s+)?(?:search(?:\\s+(?:the\\s+)?(?:web|internet|google))?\\s+(?:for\\s+)?|google\\s+)(.+)$").matchEntire(t)
  if(search!=null)return searchWeb(search.groupValues[1])
  return null
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
   if(text.isBlank()) return
   runOnUiThread{tts?.setPitch(0.72f);tts?.setSpeechRate(0.92f);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"ultron_reply")}
  }
  @JavascriptInterface fun stopSpeaking()=runOnUiThread{tts?.stop()}
  @JavascriptInterface fun saveConnection(base:String,token:String){prefs.edit().putString("base",base.trim().trimEnd('/')).putString("token",token.trim()).apply();js("connectionState","SAVED")}
  @JavascriptInterface fun getBaseUrl():String=prefs.getString("base",defaultPcBase)?:defaultPcBase
  @JavascriptInterface fun sendHomeVoiceMessage(message:String){
   val local=tryLocalCommand(message)
   if(local!=null){js("homeVoiceReply",local);return}
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
   runOnUiThread{tts?.setPitch(0.72f);tts?.setSpeechRate(0.92f);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"ultron_home_voice")}
  }
  @JavascriptInterface fun sendMessage(message:String){
   val local=tryLocalCommand(message)
   if(local!=null){js("receiveUltron",local);return}
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