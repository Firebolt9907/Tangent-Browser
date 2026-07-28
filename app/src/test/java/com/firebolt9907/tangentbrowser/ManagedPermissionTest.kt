package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.permissions.ManagedPermission
import mozilla.components.concept.engine.permission.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the string-based mapping in [ManagedPermission]. The aA site-permissions UI writes remembered
 * decisions keyed by a Gecko permission class's *simple name*, and [SitePermissionController] reads
 * requests back by the same key — so a typo, or mozilla-components renaming a `Permission.Content*`
 * class, would silently break the match (a decision set in the UI would never be honoured on the real
 * request). These tests fail loudly if the class-name constants drift from the real classes.
 */
class ManagedPermissionTest {

    // Simple names of every Gecko content-permission class a ManagedPermission may reference. Built
    // from the real classes (Java reflection, no kotlin-reflect), so a rename that changes a class's
    // simpleName is caught here — the corresponding string constant would fall out of this set.
    private val realNames: Set<String> = setOf(
        Permission.ContentGeoLocation::class.java.simpleName,
        Permission.ContentVideoCamera::class.java.simpleName,
        Permission.ContentVideoCapture::class.java.simpleName,
        Permission.ContentAudioCapture::class.java.simpleName,
        Permission.ContentAudioMicrophone::class.java.simpleName,
        Permission.ContentNotification::class.java.simpleName,
        Permission.ContentPersistentStorage::class.java.simpleName,
        Permission.ContentMediaKeySystemAccess::class.java.simpleName,
        Permission.ContentProtectedMediaId::class.java.simpleName,
        Permission.ContentLocalNetworkAccess::class.java.simpleName,
        Permission.ContentLocalDeviceAccess::class.java.simpleName,
    )

    @Test
    fun everyManagedClassNameIsARealPermissionClass() {
        ManagedPermission.entries.forEach { permission ->
            assertTrue(
                "${permission.name} has no class names",
                permission.classNames.isNotEmpty(),
            )
            permission.classNames.forEach { name ->
                assertTrue(
                    "ManagedPermission.${permission.name} references unknown class name '$name'",
                    name in realNames,
                )
            }
        }
    }

    @Test
    fun classNamesAreDisjointAcrossManagedPermissions() {
        // No permission class may back two managed entries, or setting one would toggle the other.
        val all = ManagedPermission.entries.flatMap { it.classNames }
        assertEquals("Duplicate permission class across ManagedPermission entries", all.size, all.toSet().size)
    }

    @Test
    fun labelsAndDescriptionsAreNonBlank() {
        ManagedPermission.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
    }
}
