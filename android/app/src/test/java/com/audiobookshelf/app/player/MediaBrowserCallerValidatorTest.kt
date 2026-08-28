package com.audiobookshelf.app.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaBrowserCallerValidatorTest {
  private lateinit var context: Context
  private lateinit var validator: MediaBrowserCallerValidator

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    validator = MediaBrowserCallerValidator(context)
  }

  @Test
  fun rejectsKnownButUntrustedOrdinaryPackage() {
    installPackage(KNOWN_PACKAGE, KNOWN_UID)

    assertFalse(validator.isValid(KNOWN_PACKAGE, KNOWN_UID))
  }

  @Test
  fun rejectsAllowlistedPackageWhenUidDoesNotOwnIt() {
    installPackage(KNOWN_PACKAGE, KNOWN_UID)
    installPackage(OTHER_PACKAGE, OTHER_UID)

    assertFalse(validator.isValid(KNOWN_PACKAGE, OTHER_UID))
  }

  @Test
  fun allowsPreviouslyUnknownSystemUidHost() {
    installPackage(OEM_MEDIA_PACKAGE, Process.SYSTEM_UID)

    assertTrue(validator.isValid(OEM_MEDIA_PACKAGE, Process.SYSTEM_UID))
  }

  @Test
  fun rejectsOemPreinstalledAppThatIsNotPlatformOrMediaTrusted() {
    installPackage(OEM_MEDIA_PACKAGE, OEM_UID, ApplicationInfo.FLAG_SYSTEM)

    assertFalse(validator.isValid(OEM_MEDIA_PACKAGE, OEM_UID))
  }

  @Test
  fun rejectsUnknownOrdinaryApplication() {
    installPackage(OTHER_PACKAGE, OTHER_UID)

    assertFalse(validator.isValid(OTHER_PACKAGE, OTHER_UID))
  }

  private fun installPackage(packageName: String, uid: Int, flags: Int = 0) {
    val applicationInfo = ApplicationInfo().apply {
      this.packageName = packageName
      this.uid = uid
      this.flags = flags
    }
    val packageInfo = PackageInfo().apply {
      this.packageName = packageName
      this.applicationInfo = applicationInfo
    }
    shadowOf(context.packageManager).apply {
      installPackage(packageInfo)
      setPackagesForUid(uid, packageName)
    }
  }

  private companion object {
    const val KNOWN_PACKAGE = "com.google.android.projection.gearhead"
    const val OTHER_PACKAGE = "com.example.untrusted"
    const val OEM_MEDIA_PACKAGE = "com.oem.previously.unknown.media"
    const val KNOWN_UID = 12_345
    const val OTHER_UID = 23_456
    const val OEM_UID = 34_567
  }
}
