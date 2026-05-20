package link.oppolink.bluetooth

import android.os.ParcelUuid
import java.util.UUID
import uniffi.oppolink_protocol.manufacturerId
import uniffi.oppolink_protocol.serviceUuid

/**
 * Wire-format constants exposed to the Kotlin side. The values are **sourced
 * from Rust** through UniFFI so that protocol changes can never drift between
 * the two languages — Rust is the single source of truth.
 */
object OppoLinkUuid {
    /** 128-bit BLE service UUID advertised by every OppoLink peer. */
    val SERVICE: UUID = UUID.fromString(serviceUuid())

    /** [SERVICE] wrapped for Android's BLE APIs that expect [ParcelUuid]. */
    val SERVICE_PARCEL: ParcelUuid = ParcelUuid(SERVICE)

    /**
     * 16-bit Bluetooth SIG manufacturer ID used in the manufacturer-specific
     * data field. `0xFFFF` is the public "test" value until a real allocation
     * is registered with the SIG.
     */
    val MANUFACTURER_ID: Int = manufacturerId().toInt() and 0xFFFF
}
