package dev.faizal.rtcandroid.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.faizal.rtcandroid.MainActivity
import dev.faizal.rtcandroid.R
import dev.faizal.rtcandroid.databinding.ActivityLoginBinding
import dev.faizal.rtcandroid.repository.MainRepository
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private lateinit var  views: ActivityLoginBinding
    @Inject
    lateinit var mainRepository: MainRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
    }


    private fun init(){
        views.apply {
            btn.setOnClickListener {
                mainRepository.login(
                    usernameEt.text.toString(),passwordEt.text.toString()
                ){ isDone, reason ->
                    if (!isDone){
                        Toast.makeText(this@LoginActivity, reason, Toast.LENGTH_SHORT).show()
                    }else{
                        //start moving to our main activity
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java).apply {
                            putExtra("username",usernameEt.text.toString())
                        })
                    }
                }
            }
        }
    }
}