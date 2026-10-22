package com.fp.ytmp3

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton

class JournalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        setContentView(R.layout.activity_journal)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val scroll = findViewById<ScrollView>(R.id.journalScroll)
        val text = findViewById<TextView>(R.id.journalText)
        text.text = LogStore.text().ifBlank { getString(R.string.journal_empty) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            LogStore.clear()
            text.text = getString(R.string.journal_empty)
        }
    }
}
