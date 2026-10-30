package be.arte.localdictate

import android.app.Application
import be.arte.localdictate.session.DictationController

class DictateApplication : Application() {
    // Owned by this Application, never a static reference to a previous test/process Application.
    val controller by lazy { DictationController(this) }
}
