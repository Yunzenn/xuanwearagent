package com.aiwatch.probe

import android.app.Application
import com.aiwatch.protocol.DeviceIdentityStore
import java.io.File

class ProbeApplication : Application() {
    val productStore by lazy { com.aiwatch.probe.product.ProductStore(File(noBackupFilesDir, "product")) }
    // One DataStore per process, outside backup/transfer storage.
    val identityStore: DeviceIdentityStore by lazy {
        DeviceIdentityStore(File(noBackupFilesDir, "device-identity.bin"))
    }
}
