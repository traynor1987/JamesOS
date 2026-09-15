package uk.co.james

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Keeps production background scheduling out of isolated instrumentation tests. */
class JamesTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application = super.newApplication(cl, JamesTestApplication::class.java.name, context)
}

class JamesTestApplication : Application()
