package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var page = 0
    private var pageUri: Uri? = null
    private lateinit var reader: ReaderView
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var status: TextView
    private lateinit var openButton: Button
    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val marks = mutableListOf<Mark>()
    private val undoStack = mutableListOf<List<Mark>>()
    private val colors = intArrayOf(0xFFFFEB3B.toInt(),0xFF4CAF50.toInt(),0xFF2196F3.toInt(),0xFFE91E63.toInt(),0xFFFF9800.toInt(),0xFF9C27B0.toInt(),0xFFF44336.toInt(),0xFF00BCD4.toInt())
    private val colorNames = arrayOf("أصفر","أخضر","أزرق","وردي","برتقالي","بنفسجي","أحمر","سماوي")
    private var chosenColor = 0
    private var opacity = 55
    private var soundMode = 1
    private var night = false
    private var fitWidth = true
    private var keepAwake = true
    private var brightness = 0.5f
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var grayscale = false
    // 0 = mouse/navigation, 1 = highlight
    private var interactionMode = 0
    private val pageTitles = mutableMapOf<Int,String>()
    private val pageComments = mutableMapOf<Int,String>()
    private val textBoxes = mutableListOf<TextBox>()
    private var sidebar: HorizontalScrollView? = null
    private var sidebarVisible = false
    data class TextBox(val page:Int,var x:Float,var y:Float,var text:String,var color:Int=Color.BLACK,var size:Float=18f)
    data class Mark(val page:Int, val left:Float, val top:Float, val right:Float, val bottom:Float, var color:Int, var alpha:Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalBrightness = window.attributes.screenBrightness
        soundMode = prefs.getInt("soundMode",1)
        chosenColor = prefs.getInt("color",0)
        opacity = prefs.getInt("opacity",55)
        night = prefs.getBoolean("night",false)
        keepAwake = prefs.getBoolean("awake",true)
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xFF181C24.toInt()) }
        openButton = Button(this).apply { text="فتح ملف PDF"; setOnClickListener { choosePdf() } }
        root.addView(openButton, LinearLayout.LayoutParams(-1,-2))
        val readerFrame = FrameLayout(this)
        reader = ReaderView()
        readerFrame.addView(reader, FrameLayout.LayoutParams(-1,-1))
        toolbar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(4,3,4,3); setBackgroundColor(0xEE202733.toInt()) }
        val compact=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        compact.addView(button("‹"){showPage(page-1)},LinearLayout.LayoutParams(42,-2))
        status=TextView(this).apply { text="لم يتم فتح ملف"; gravity=Gravity.CENTER; textSize=12f; setTextColor(Color.WHITE) }
        compact.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        compact.addView(button("›"){showPage(page+1)},LinearLayout.LayoutParams(42,-2))
        compact.addView(button("⋮"){showMoreOptions()},LinearLayout.LayoutParams(42,-2))
        toolbar.addView(compact,LinearLayout.LayoutParams(-1,-2))
        readerFrame.addView(toolbar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val rail=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(3,6,3,6); background=android.graphics.drawable.GradientDrawable().apply { setColor(0xEE202733.toInt()); cornerRadius=18f } }
        fun tool(label:String,action:()->Unit)=Button(this).apply { text=label; textSize=10f; minHeight=42; minWidth=42; setPadding(2,0,2,0); setTextColor(Color.WHITE); setBackgroundColor(0xFF344052.toInt()); setOnClickListener { action() } }
        rail.addView(tool("🖱"){setMouseMode()})
        rail.addView(tool("🖍"){if(renderer!=null)showHighlightStyles() else toast("افتح ملف PDF أولاً")})
        rail.addView(tool("⌫"){setEraserMode()})
        readerFrame.addView(rail,FrameLayout.LayoutParams(48,-2,Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin=4 })
        root.addView(readerFrame,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        if(keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val last=prefs.getString("lastUri",null)
        if(last!=null) openUri(Uri.parse(last),false)
    }

    private fun button(label:String, action:()->Unit)=Button(this).apply { text=label; textSize=12f; minHeight=38; setPadding(2,0,2,0); setOnClickListener{action()} }
    private fun choosePdf(){ val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/pdf";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)}; startActivityForResult(i,77) }
    @Deprecated("Legacy document picker callback for Android 7")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==77&&resultCode==RESULT_OK){val u=data?.data?:return;try{contentResolver.takePersistableUriPermission(u,data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION);openUri(u,true)}catch(e:Exception){toast("تعذر فتح الملف: ${e.message}")}}}
    private fun openUri(uri:Uri,save:Boolean){
        try{closeDocument();pfd=contentResolver.openFileDescriptor(uri,"r")?:throw IOException("ملف غير متاح");renderer=PdfRenderer(pfd!!);pageUri=uri;page=0
            undoStack.clear();val key=uri.toString();loadMarks(key);loadPageNotes(key);if(save)prefs.edit().putString("lastUri",key).apply();showPage(0)
        }catch(e:Exception){toast("تعذر فتح PDF: ${e.message}")}
    }
    private fun showPage(index:Int){val r=renderer?:return;if(index !in 0 until r.pageCount)return;try{val p=r.openPage(index);val maxSide=1250;val s=min( min(resources.displayMetrics.widthPixels.toFloat()/p.width,maxSide.toFloat()/p.width),maxSide.toFloat()/p.height);val w=max(1,(p.width*s).toInt());val h=max(1,(p.height*s).toInt());val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);p.close();reader.setPage(b);page=index;status.text="${index+1} / ${r.pageCount}";reader.invalidate();openButton.visibility=View.GONE;toolbar.visibility=View.VISIBLE;window.decorView.systemUiVisibility= (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)}catch(e:Exception){toast("خطأ في الصفحة: ${e.message}")}}
    private fun closeDocument(){readerOrNull()?.clearPage();renderer?.close();renderer=null;pfd?.close();pfd=null;pageUri=null}
    private fun readerOrNull():ReaderView?=if(::reader.isInitialized)reader else null
    private fun closeReaderUi(){closeDocument();val a=window.attributes;a.screenBrightness=originalBrightness;window.attributes=a;openButton.visibility=View.VISIBLE;toolbar.visibility=View.VISIBLE;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_VISIBLE;status.text="لم يتم فتح ملف"}
    override fun onBackPressed(){if(renderer!=null)closeReaderUi() else super.onBackPressed()}
    override fun onDestroy(){closeDocument();val a=window.attributes;a.screenBrightness=originalBrightness;window.attributes=a;super.onDestroy()}
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun loadMarks(key:String){marks.clear();try{val a=JSONArray(prefs.getString("marks:"+key,"[]"));for(i in 0 until a.length()){val o=a.getJSONObject(i);marks.add(Mark(o.getInt("p"),o.getDouble("l").toFloat(),o.getDouble("t").toFloat(),o.getDouble("r").toFloat(),o.getDouble("b").toFloat(),o.getInt("c"),o.getInt("a")))}}catch(_:Exception){}}
    private fun rememberUndo(){undoStack.add(marks.map{it.copy()});if(undoStack.size>20)undoStack.removeAt(0)}
    private fun undo(){if(undoStack.isEmpty()){toast("مفيش حاجة للتراجع عنها");return};marks.clear();marks.addAll(undoStack.removeAt(undoStack.lastIndex).map{it.copy()});saveMarks();reader.invalidate();toast("تم التراجع عن آخر تعديل")}
    private fun loadPageNotes(key:String){pageTitles.clear();pageComments.clear();textBoxes.clear();try{val o=JSONObject(prefs.getString("pageNotes:"+key,"{}"));val t=o.optJSONObject("titles")?:JSONObject();val c=o.optJSONObject("comments")?:JSONObject();val b=o.optJSONArray("boxes")?:JSONArray();t.keys().forEach{pageTitles[it.toIntOrNull()?:-1]=t.optString(it)};c.keys().forEach{pageComments[it.toIntOrNull()?:-1]=c.optString(it)};for(i in 0 until b.length()){val x=b.getJSONObject(i);textBoxes.add(TextBox(x.getInt("p"),x.getDouble("x").toFloat(),x.getDouble("y").toFloat(),x.getString("t"),x.optInt("c",Color.BLACK),x.optDouble("s",18.0).toFloat()))}}catch(_:Exception){}}
    private fun savePageNotes(){val key=pageUri?.toString()?:return;val t=JSONObject();pageTitles.forEach{(p,v)->t.put(p.toString(),v)};val c=JSONObject();pageComments.forEach{(p,v)->c.put(p.toString(),v)};val b=JSONArray();textBoxes.forEach{x->b.put(JSONObject().put("p",x.page).put("x",x.x.toDouble()).put("y",x.y.toDouble()).put("t",x.text).put("c",x.color).put("s",x.size.toDouble()))};prefs.edit().putString("pageNotes:"+key,JSONObject().put("titles",t).put("comments",c).put("boxes",b).toString()).apply();reader.invalidate()}
    private fun editPageTitle(){if(renderer==null)return;val input=EditText(this).apply{setText(pageTitles[page]?:"");hint="اكتب عنوان الصفحة"};AlertDialog.Builder(this).setTitle("عنوان الصفحة").setView(input).setPositiveButton("حفظ"){_,_->val v=input.text.toString().trim();if(v.isEmpty())pageTitles.remove(page)else pageTitles[page]=v;savePageNotes();refreshThumbnails()}.setNegativeButton("إلغاء",null).show()}
    private fun editPageComment(){if(renderer==null)return;val input=EditText(this).apply{setText(pageComments[page]?:"");hint="اكتب تعليقك";minLines=3;gravity=Gravity.TOP};AlertDialog.Builder(this).setTitle("تعليق الصفحة").setView(input).setPositiveButton("حفظ"){_,_->val v=input.text.toString().trim();if(v.isEmpty())pageComments.remove(page)else pageComments[page]=v;savePageNotes();toast("تم حفظ التعليق")}.setNegativeButton("إلغاء",null).show()}
    private fun addTextBox(){if(renderer==null)return;val input=EditText(this).apply{hint="اكتب النص هنا";minLines=2;gravity=Gravity.TOP};AlertDialog.Builder(this).setTitle("مربع نص جديد").setView(input).setPositiveButton("إضافة"){_,_->val v=input.text.toString().trim();if(v.isNotEmpty()){textBoxes.add(TextBox(page,0.12f,0.18f,v));savePageNotes();toast("تمت إضافة مربع النص")}}.setNegativeButton("إلغاء",null).show()}
    private fun searchNotes(){if(renderer==null)return;val input=EditText(this).apply{hint="ابحث في العناوين والتعليقات"};AlertDialog.Builder(this).setTitle("البحث في ملاحظاتك").setView(input).setPositiveButton("بحث"){_,_->val q=input.text.toString().trim();if(q.isNotEmpty()){val found=(pageTitles.filterValues{it.contains(q,true)}.keys+pageComments.filterValues{it.contains(q,true)}.keys).distinct().sorted();if(found.isEmpty())toast("لا توجد نتائج")else AlertDialog.Builder(this).setTitle("نتائج البحث").setItems(found.map{"صفحة "+(it+1)+": "+(pageTitles[it]?:pageComments[it]?:"").take(45)}.toTypedArray()){_,i->showPage(found[i])}.setNegativeButton("إغلاق",null).show()}}.setNegativeButton("إلغاء",null).show()}
    private fun toggleThumbnails(){if(renderer==null)return;if(sidebarVisible){root.removeView(sidebar);sidebar=null;sidebarVisible=false;return};val strip=HorizontalScrollView(this);val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(4,4,4,4)};strip.addView(row);sidebar=strip;sidebarVisible=true;root.addView(strip,root.indexOfChild(reader),LinearLayout.LayoutParams(-1,108));fillThumbnails(row)}
    private fun refreshThumbnails(){if(sidebarVisible){val row=sidebar?.getChildAt(0) as? LinearLayout?:return;fillThumbnails(row)}}
    private fun fillThumbnails(row:LinearLayout){row.removeAllViews();val r=renderer?:return;for(i in 0 until r.pageCount){val cell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(3,2,3,2);setBackgroundColor(if(i==page)0xFFB3E5FC.toInt() else Color.TRANSPARENT)};val label=TextView(this).apply{text=(i+1).toString();textSize=11f;gravity=Gravity.CENTER};val thumb=ImageView(this).apply{layoutParams=LinearLayout.LayoutParams(64,76);scaleType=ImageView.ScaleType.FIT_CENTER};try{val p=r.openPage(i);val w=64;val h=max(1,(p.height.toFloat()/p.width*w).toInt());val bm=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);bm.eraseColor(Color.WHITE);p.render(bm,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);p.close();thumb.setImageBitmap(bm)}catch(_:Exception){};cell.addView(thumb);cell.addView(label);cell.setOnClickListener{showPage(i);refreshThumbnails()};row.addView(cell)}}
    private fun saveMarks(){val key=pageUri?.toString()?:return;val a=JSONArray();marks.forEach{m->a.put(JSONObject().put("p",m.page).put("l",m.left.toDouble()).put("t",m.top.toDouble()).put("r",m.right.toDouble()).put("b",m.bottom.toDouble()).put("c",m.color).put("a",m.alpha))};prefs.edit().putString("marks:"+key,a.toString()).apply()}
    private fun showMoreOptions(){
        val items=arrayOf("اختيار لون التمييز","تراجع","الصفحات المصغرة","علامة صفحة","عنوان الصفحة","تعليق الصفحة","إضافة مربع نص","بحث في ملاحظاتي","الانتقال السريع","الوضع الليلي","أبيض وأسود","ملاءمة العرض","الإعدادات")
        AlertDialog.Builder(this).setTitle("أدوات القراءة").setItems(items){_,i->when(i){
            0->showColorSettings();1->undo();2->toggleThumbnails();3->toggleBookmark();4->editPageTitle();5->editPageComment();6->addTextBox();7->searchNotes();8->showQuickPageDialog()
            9->{night=!night;prefs.edit().putBoolean("night",night).apply();reader.invalidate()}
            10->{grayscale=!grayscale;reader.invalidate()}
            11->{fitWidth=!fitWidth;reader.invalidate()}
            12->showSettings()
        }}.show()
    }
    private var highlightStyle = 0
    private fun showHighlightHelp(){showHighlightStyles()}
    private fun showHighlightStyles(){AlertDialog.Builder(this).setTitle("طريقة التمييز").setSingleChoiceItems(arrayOf("تمييز ذكي للجملة كاملة","فرشاة حرة بخطوط مستقيمة"),highlightStyle){d,w->highlightStyle=w;setHighlightMode();d.dismiss()}.setNegativeButton("إلغاء",null).show()}
    private fun setHighlightMode(){if(renderer==null){toast("افتح ملف PDF أولاً");return};interactionMode=1;toast(if(highlightStyle==0)"وضع تمييز الجمل: اسحب فوق السطر" else "وضع الفرشاة: اسحب لرسم خطوط مستقيمة")}
    private fun setMouseMode(){interactionMode=0;toast("وضع الماوس: اضغط على التمييز لعرض خياراته")}
    private fun setEraserMode(){if(renderer==null){toast("افتح ملف PDF أولاً");return};interactionMode=2;toast("الممحاة: اضغط على تمييز أو مربع نص لحذفه")}
    private fun showQuickPageDialog(){
        val r=renderer?:run{toast("افتح ملف PDF أولاً");return}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,4)}
        val label=TextView(this).apply{textSize=16f;gravity=Gravity.CENTER}
        val seek=SeekBar(this).apply{max=(r.pageCount-1).coerceAtLeast(0);progress=page}
        fun update(){label.text="الصفحة ${seek.progress+1} من ${r.pageCount}"}
        box.addView(label);box.addView(seek);update()
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){update()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })
        AlertDialog.Builder(this).setTitle("الانتقال السريع").setView(box)
            .setPositiveButton("انتقال"){_,_->showPage(seek.progress)}
            .setNegativeButton("إلغاء",null).show()
    }
    private fun showColorSettings(){val names=colorNames.toMutableList();AlertDialog.Builder(this).setTitle("لون التمييز الافتراضي").setSingleChoiceItems(names.toTypedArray(),chosenColor){d,which->chosenColor=which;prefs.edit().putInt("color",which).apply();d.dismiss();showOpacityDialog()}.setNegativeButton("إلغاء",null).show()}
    private fun showOpacityDialog(){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,4)};val label=TextView(this);val seek=SeekBar(this).apply{max=80;progress=opacity-10};box.addView(label);box.addView(seek);fun update(){label.text="الشفافية: ${10+seek.progress}% (قلّل الرقم لتقليل الشفافية)"};seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){update()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}});update();AlertDialog.Builder(this).setTitle("شفافية التمييز").setView(box).setPositiveButton("حفظ"){_,_->opacity=10+seek.progress;prefs.edit().putInt("opacity",opacity).apply()}.setNegativeButton("إلغاء",null).show()}
    private fun showSettings(){val items=arrayOf("أزرار الصوت: التالي/السابق","أزرار الصوت طبيعية","أزرار الصوت للتحكم في السطوع","منع إطفاء الشاشة أثناء القراءة","تطبيق اللون والشفافية الافتراضيين على كل التمييزات");AlertDialog.Builder(this).setTitle("إعدادات القراءة").setItems(items){_,which->when(which){0,1,2->{soundMode=which;prefs.edit().putInt("soundMode",which).apply();toast("تم حفظ إعداد أزرار الصوت")};3->{keepAwake=!keepAwake;prefs.edit().putBoolean("awake",keepAwake).apply();if(keepAwake)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);toast(if(keepAwake)"منع إطفاء الشاشة مفعّل" else "تم إيقاف منع إطفاء الشاشة")};4->{rememberUndo();marks.forEach{it.color=colors[chosenColor];it.alpha=opacity};saveMarks();reader.invalidate();toast("تم تطبيق الإعداد على التمييزات")}}}.show()}
    private fun toggleBookmark(){if(renderer==null)return;val key="bookmarks:"+(pageUri?.toString()?:return);val set=prefs.getStringSet(key,emptySet())!!.toMutableSet();if(set.contains(page.toString()))set.remove(page.toString())else set.add(page.toString());prefs.edit().putStringSet(key,set).apply();toast(if(set.contains(page.toString()))"تم حفظ علامة الصفحة" else "تم حذف علامة الصفحة");showBookmarksMenu()}
    private fun showBookmarksMenu(){val key="bookmarks:"+(pageUri?.toString()?:return);val list=prefs.getStringSet(key,emptySet())!!.mapNotNull{it.toIntOrNull()}.sorted();if(list.isEmpty()){toast("لا توجد علامات محفوظة");return};AlertDialog.Builder(this).setTitle("الصفحات المحفوظة").setItems(list.map{"صفحة ${it+1}"}.toTypedArray()){_,which->showPage(list[which])}.setNegativeButton("إغلاق",null).show()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{if(renderer!=null&&(keyCode==KeyEvent.KEYCODE_VOLUME_UP||keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)){when(soundMode){0->showPage(page+if(keyCode==KeyEvent.KEYCODE_VOLUME_UP)1 else -1);1->return super.onKeyDown(keyCode,event);2->{brightness=(brightness+if(keyCode==KeyEvent.KEYCODE_VOLUME_UP)0.08f else -0.08f).coerceIn(0.05f,1f);val a=window.attributes;a.screenBrightness=brightness;window.attributes=a}};return true};return super.onKeyDown(keyCode,event)}

    private inner class ReaderView:View(this@MainActivity){
        private var bitmap:Bitmap?=null
        private var zoom=1f
        private var panX=0f;private var panY=0f
        private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f
        private var selecting=false;private var longStart=0L
        private var activeRect:RectF?=null
        private val detector=ScaleGestureDetector(this@MainActivity,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean{val old=zoom;val next=(zoom*d.scaleFactor).coerceIn(1f,4f);val factor=next/old;panX=d.focusX-(d.focusX-panX)*factor;panY=d.focusY-(d.focusY-panY)*factor;zoom=next;clampPan();invalidate();return true}})
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var content=RectF()
        fun setPage(b:Bitmap){bitmap?.recycle();bitmap=b;zoom=1f;panX=0f;panY=0f;invalidate()}
        fun clearPage(){bitmap?.recycle();bitmap=null;invalidate()}
        private fun imageRect():RectF{val b=bitmap?:return RectF();val scale=min(width.toFloat()/b.width,height.toFloat()/b.height);val s=if(fitWidth)width.toFloat()/b.width else scale;val w=b.width*s;val h=b.height*s;return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)}
        override fun onDraw(c:Canvas){super.onDraw(c);val b=bitmap?:run{c.drawColor(if(night)Color.DKGRAY else Color.WHITE);return};content=imageRect();val save=c.save();c.translate(panX,panY);c.scale(zoom,zoom);val filter=when{night->ColorMatrixColorFilter(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f));grayscale->ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)});else->null};paint.colorFilter=filter;paint.alpha=255;c.drawBitmap(b,null,content,paint);paint.colorFilter=null
            marks.filter{it.page==page}.forEach{m->paint.color=m.color;paint.alpha=((100-m.alpha)*255/100).coerceIn(0,255);c.drawRect(content.left+m.left*content.width(),content.top+m.top*content.height(),content.left+m.right*content.width(),content.top+m.bottom*content.height(),paint)}
            textBoxes.filter{it.page==page}.forEach{tb->paint.color=tb.color;paint.alpha=255;paint.textSize=tb.size;paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);c.drawText(tb.text,content.left+tb.x*content.width(),content.top+tb.y*content.height(),paint)}
            activeRect?.let{paint.color=colors[chosenColor];paint.alpha=((100-opacity)*255/100).coerceIn(0,255);c.drawRect(it,paint)};c.restoreToCount(save);paint.alpha=255}
        private fun toImage(x:Float,y:Float):Pair<Float,Float>{val xx=(x-panX)/zoom;val yy=(y-panY)/zoom;return Pair((xx-content.left)/content.width().coerceAtLeast(1f),(yy-content.top)/content.height().coerceAtLeast(1f))}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            detector.onTouchEvent(e)
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;lastX=e.x;lastY=e.y;longStart=System.currentTimeMillis();selecting=false;parent?.requestDisallowInterceptTouchEvent(true);return true}
                MotionEvent.ACTION_POINTER_DOWN->{selecting=false;return true}
                MotionEvent.ACTION_MOVE->{
                    if(zoom>1f&&e.pointerCount==1&&!selecting){panX+=e.x-lastX;panY+=e.y-lastY;clampPan();invalidate();lastX=e.x;lastY=e.y;return true}
                    if(interactionMode==1&&e.pointerCount==1&&zoom==1f&&System.currentTimeMillis()-longStart>250){
                        selecting=true;activeRect=RectF(min(downX,e.x),min(downY,e.y),max(downX,e.x),max(downY,e.y));invalidate()
                    }
                    lastX=e.x;lastY=e.y;return true
                }
                MotionEvent.ACTION_UP->{
                    if(selecting){
                        val rect=activeRect;activeRect=null
                        if(rect!=null&&rect.width()>8&&rect.height()>5)addSmartMarks(rect)
                        invalidate()
                    }else if(zoom==1f&&abs(e.x-downX)>55&&abs(e.x-downX)>abs(e.y-downY)*1.25f){
                        showPage(page+if(e.x<downX)1 else -1)
                    }else{
                        val p=toImage(e.x,e.y)
                        val found=marks.lastOrNull{it.page==page&&p.first>=it.left&&p.first<=it.right&&p.second>=it.top&&p.second<=it.bottom}
                        val textBox=textBoxes.lastOrNull{it.page==page&&p.first>=it.x-0.03f&&p.first<=it.x+0.8f&&p.second>=it.y-0.08f&&p.second<=it.y+0.08f}
                        when(interactionMode){
                            1->{if(found==null)addLineAt(e.x,e.y)}
                            2->{if(found!=null){rememberUndo();marks.remove(found);saveMarks();reader.invalidate();refreshThumbnails();toast("تم مسح التمييز")}else if(textBox!=null){textBoxes.remove(textBox);savePageNotes();toast("تم مسح مربع النص")}else toast("مفيش عنصر تحت الممحاة")}
                            else->{if(found!=null)editMark(found)else if(textBox!=null){val input=EditText(this@MainActivity).apply{setText(textBox.text)};AlertDialog.Builder(this@MainActivity).setTitle("تعديل مربع النص").setView(input).setPositiveButton("حفظ"){_,_->textBox.text=input.text.toString();savePageNotes()}.setNeutralButton("حذف"){_,_->textBoxes.remove(textBox);savePageNotes()}.setNegativeButton("إلغاء",null).show()}}
                        }
                    }
                    parent?.requestDisallowInterceptTouchEvent(false);return true
                }
                MotionEvent.ACTION_CANCEL->{activeRect=null;parent?.requestDisallowInterceptTouchEvent(false);invalidate();return true}
            }
            return true
        }
        private fun clampPan(){val r=imageRect();val minX=width-r.right*zoom;val maxX=-r.left*zoom;val minY=height-r.bottom*zoom;val maxY=-r.top*zoom;panX=if(zoom<=1f)0f else panX.coerceIn(minX.coerceAtMost(maxX),maxX.coerceAtLeast(minX));panY=if(zoom<=1f)0f else panY.coerceIn(minY.coerceAtMost(maxY),maxY.coerceAtLeast(minY))}
        private fun addLineAt(x:Float,y:Float){
            val b=bitmap?:return
            val p=toImage(x,y)
            if(p.first !in 0f..1f||p.second !in 0f..1f)return
            val imageY=(p.second*b.height).toInt().coerceIn(0,b.height-1)
            val radius=max(5,(b.height*0.012f).toInt())
            val top=(imageY-radius).coerceAtLeast(0)
            val bottom=(imageY+radius).coerceAtMost(b.height)
            val rect=RectF(content.left,content.top+top.toFloat()/b.height*content.height(),content.right,content.top+bottom.toFloat()/b.height*content.height())
            addSmartMarks(rect)
        }
        // Detect text rows and word-like horizontal groups from the rendered page.
        // This is image geometry, not OCR, so it works offline and keeps memory use low.
        private fun addSmartMarks(r:RectF){
            val b=bitmap?:return
            val a=toImage(r.left,r.top);val z=toImage(r.right,r.bottom)
            val sx1=(min(a.first,z.first)*b.width).toInt().coerceIn(0,b.width-1)
            val sx2=(max(a.first,z.first)*b.width).toInt().coerceIn(sx1+1,b.width)
            var sy1=(min(a.second,z.second)*b.height).toInt().coerceIn(0,b.height-1)
            var sy2=(max(a.second,z.second)*b.height).toInt().coerceIn(sy1+1,b.height)
            val inkThreshold=178
            fun dark(x:Int,y:Int):Boolean{
                val px=b.getPixel(x,y)
                return (Color.red(px)*30+Color.green(px)*59+Color.blue(px)*11)/100 < inkThreshold
            }
            val rows=ArrayList<Int>()
            val rowThreshold=max(1,((sx2-sx1)*0.0015f).toInt())
            for(y in sy1 until sy2){
                var count=0;var x=sx1
                while(x<sx2){if(dark(x,y))count++;x+=2}
                if(count>=rowThreshold)rows.add(y)
            }
            // A short gesture snaps to the nearest printed line.
            if(rows.isEmpty()){
                val cy=((sy1+sy2)/2).coerceIn(0,b.height-1)
                var best=-1;var bestCount=0
                for(y in max(0,cy-28)..min(b.height-1,cy+28)){
                    var count=0;var x=sx1
                    while(x<sx2){if(dark(x,y))count++;x+=2}
                    if(count>bestCount){bestCount=count;best=y}
                }
                if(best>=0&&bestCount>0){
                    sy1=max(0,best-12);sy2=min(b.height,best+13)
                    for(y in sy1 until sy2){var count=0;var x=sx1;while(x<sx2){if(dark(x,y))count++;x+=2};if(count>=rowThreshold)rows.add(y)}
                }
            }
            if(rows.isEmpty()){toast("قرّب التحديد من الكلام وحاول تاني");return}
            val lines=mutableListOf<Pair<Int,Int>>()
            var lineStart=rows.first();var previous=rows.first()
            for(y in rows.drop(1)){if(y-previous>5){lines.add(Pair(lineStart,previous+1));lineStart=y};previous=y}
            lines.add(Pair(lineStart,previous+1))
            val detected=mutableListOf<Mark>()
            val wordGap=max(4,(b.width*0.0045f).toInt())
            for((top0,bottom0) in lines){
                val top=max(0,top0-2);val bottom=min(b.height,bottom0+3)
                val columnThreshold=max(1,((bottom-top)*0.035f).toInt())
                val occupied=ArrayList<Int>()
                for(x in 0 until b.width){
                    var count=0;var y=top
                    while(y<bottom){if(dark(x,y))count++;y+=2}
                    if(count>=columnThreshold)occupied.add(x)
                }
                if(occupied.isEmpty())continue
                val runs=mutableListOf<Pair<Int,Int>>()
                var left=occupied.first();var prev=left
                for(x in occupied.drop(1)){if(x-prev>wordGap){runs.add(Pair(left,prev+1));left=x};prev=x}
                runs.add(Pair(left,prev+1))
                for((wl,wr) in runs){
                    if(wr<sx1||wl>sx2)continue
                    val l=max(0,wl-2).toFloat()/b.width
                    val rr=min(b.width,wr+2).toFloat()/b.width
                    val t=top.toFloat()/b.height
                    val bb=bottom.toFloat()/b.height
                    if(rr>l&&bb>t)detected.add(Mark(page,l,t,rr,bb,colors[chosenColor],opacity))
                }
            }
            if(detected.isEmpty()){toast("مش قادر أحدد كلمات واضحة في المنطقة دي");return}
            rememberUndo();marks.addAll(detected);saveMarks();invalidate()
            toast("تم تمييز "+detected.size+" جزء من النص")
        }
        private fun editMark(m:Mark){AlertDialog.Builder(this@MainActivity).setTitle("تعديل التمييز").setItems(arrayOf("تغيير اللون","حذف التمييز")){_,which->if(which==0){AlertDialog.Builder(this@MainActivity).setTitle("اختر اللون").setSingleChoiceItems(colorNames,colors.indexOf(m.color).coerceAtLeast(0)){d,i->rememberUndo();m.color=colors[i];saveMarks();invalidate();d.dismiss();showOpacityForMark(m)}.setNegativeButton("إلغاء",null).show()}else{rememberUndo();marks.remove(m);saveMarks();invalidate();toast("تم حذف التمييز")}}.setNegativeButton("إلغاء",null).show()}
        private fun showOpacityForMark(m:Mark){val seek=SeekBar(this@MainActivity).apply{max=80;progress=(m.alpha-10).coerceIn(0,80)};AlertDialog.Builder(this@MainActivity).setTitle("شفافية التمييز").setView(seek).setPositiveButton("حفظ"){_,_->rememberUndo();m.alpha=10+seek.progress;saveMarks();invalidate()}.setNegativeButton("إلغاء",null).show()}
    }
}
