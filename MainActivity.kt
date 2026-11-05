package com.example.autopay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : Activity() {
    private val smsTx = mutableListOf<Txn>()
    private val csvTx = mutableListOf<Txn>()
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private val ink = Color.parseColor("#10302F")
    private val mute = Color.parseColor("#5A7270")
    private val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(48), dp(18), dp(32))
        }
        col.addView(TextView(this).apply {
            text = "Autopay Detector"; textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(ink)
        })
        col.addView(TextView(this).apply {
            text = "Aapke SMS se pata lagata hai ki kaun si autopay, mandate ya subscription chal rahi hai. Sab kuch phone ke andar hi hota hai."
            setTextColor(mute); setPadding(0, dp(6), 0, dp(16))
        })
        col.addView(Button(this).apply { text = "SMS scan karo"; setOnClickListener { scanSms() } })
        col.addView(Button(this).apply { text = "Statement CSV chuno (optional)"; setOnClickListener { pickCsv() } })
        info = TextView(this).apply { setTextColor(ink); setPadding(0, dp(14), 0, dp(10)); textSize = 16f }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(info); col.addView(results)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#EDF2F1")); addView(col) })
    }

    private fun scanSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS), 1); return
        }
        info.text = "SMS padh raha hoon..."
        Thread {
            val list = mutableListOf<Txn>()
            val since = System.currentTimeMillis() - 200L * 86_400_000L
            try {
                contentResolver.query(
                    Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
                    "date > ?", arrayOf(since.toString()), "date DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        Detector.fromSms(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2))?.let { list.add(it) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { info.text = "SMS nahi padh paya: ${e.message}" }; return@Thread
            }
            runOnUiThread { smsTx.clear(); smsTx.addAll(list); show() }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == 1 && r.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanSms()
        else info.text = "SMS permission nahi mili. App info mein jaakar SMS permission allow karo (README dekho)."
    }

    private fun pickCsv() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, 2)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != 2 || res != RESULT_OK) return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val list = Detector.fromCsv(text)
            if (list.isEmpty()) { info.text = "CSV mein Date, Description aur Debit/Amount columns nahi mile."; return }
            csvTx.clear(); csvTx.addAll(list); show()
        } catch (e: Exception) { info.text = "File nahi khul paayi: ${e.message}" }
    }

    private fun show() {
        results.removeAllViews()
        val found = Detector.analyse(smsTx + csvTx)
        val total = found.sumOf { it.monthly }
        info.text = if (found.isEmpty())
            "Koi autopay jaisa pattern nahi mila (${smsTx.size} SMS, ${csvTx.size} statement entries dekhe)."
        else "${found.size} autopay jaisi cheezein mili. Active ka approx kharcha: ₹${Math.round(total)} / mahina"
        for (f in found) {
            val color = if (f.score >= 70) "#C2410C" else if (f.score >= 45) "#B7791F" else "#5A7270"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(2), Color.parseColor(color))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = "${f.name}   ₹${Math.round(f.avg)}" + (f.freq?.let { " / $it" } ?: "")
                textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(ink)
            })
            val lines = mutableListOf("Status: ${f.status}", "Last: ${fmt.format(f.last)}")
            if (f.next != null && f.status == "Active") lines.add("Agla payment: lagbhag ${fmt.format(f.next)}")
            if (f.flags.isNotEmpty()) lines.add("Mila: " + f.flags.joinToString(", "))
            lines.add("${f.count} baar dikha")
            card.addView(TextView(this).apply { text = lines.joinToString("\n"); setTextColor(mute) })
            results.addView(card)
        }
    }
}
