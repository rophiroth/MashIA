package org.psyhackers.mashia.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import org.psyhackers.mashia.MainActivity
import org.psyhackers.mashia.R

class AuthActivity : AppCompatActivity() {
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth
    private var useFirebase: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val already = GoogleSignIn.getLastSignedInAccount(this)
        if (already != null) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_auth)

        // Detecta si existe default_web_client_id (generado por google-services.json)
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        useFirebase = resId != 0

        if (!useFirebase) {
            Toast.makeText(
                this,
                "Tip: agrega app/google-services.json y habilita Google en Firebase",
                Toast.LENGTH_LONG
            ).show()
        }

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (useFirebase) {
            val webClientId = getString(resId)
            gsoBuilder.requestIdToken(webClientId)
        }
        val gso = gsoBuilder.build()
        googleClient = GoogleSignIn.getClient(this, gso)
        auth = FirebaseAuth.getInstance()

        findViewById<Button>(R.id.btn_google).setOnClickListener {
            // Activity Result API
            googleLauncher.launch(googleClient.signInIntent)
        }
        findViewById<Button>(R.id.btn_apple).setOnClickListener {
            Toast.makeText(this, "Apple sign-in coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_fb).setOnClickListener {
            Toast.makeText(this, "Facebook sign-in coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data: Intent? = result.data
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            if (useFirebase) {
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                auth.signInWithCredential(credential)
                    .addOnSuccessListener { goToMain() }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Firebase auth failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            } else {
                // Sin Firebase, simplemente continúa (requiere configurar OAuth Android Client para evitar error 10)
                goToMain()
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Google sign-in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object { private const val RC_SIGN_IN = 9001 }
}
