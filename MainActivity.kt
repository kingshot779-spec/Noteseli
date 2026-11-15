package com.eliosnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.eliosnotes.app.data.NotesDatabase
import com.eliosnotes.app.data.NotesRepository
import com.eliosnotes.app.ui.NotesApp
import com.eliosnotes.app.ui.theme.EliosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = NotesRepository(NotesDatabase.getInstance(applicationContext))
        setContent {
            EliosTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotesApp(repository)
                }
            }
        }
    }
}
