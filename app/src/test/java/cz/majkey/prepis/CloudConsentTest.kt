package cz.majkey.prepis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConsentTest {
    @Test
    fun legacyJobsCannotBeUnlockedByLaterApproval() {
        assertFalse(cloudUploadAllowed(0, false, true, false))
        assertFalse(cloudUploadAllowed(0, true, true, false))
    }

    @Test
    fun oneRecordingApprovalDoesNotAuthorizeAnotherJob() {
        assertTrue(cloudUploadAllowed(1, true, false, false))
        assertFalse(cloudUploadAllowed(1, false, false, false))
    }

    @Test
    fun automaticJobsRequireCurrentApproval() {
        assertTrue(cloudUploadAllowed(1, false, true, false))
        assertFalse(cloudUploadAllowed(1, false, false, false))
    }

    @Test
    fun cancellationBlocksBothKindsOfUpload() {
        assertFalse(cloudUploadAllowed(1, true, false, true))
        assertFalse(cloudUploadAllowed(1, false, true, true))
    }
}
