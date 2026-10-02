package com.mobidesk.mobidesk_app

/**
 * Single source of truth constants for Android Open Accessory (AOA 2.0) handshake.
 * Corresponds to aoa_identity.json, accessory_filter.xml, and mobidesk_dock.py.
 */
object AoaConstants {
    const val MANUFACTURER = "MobiDesk"
    const val MODEL = "MobiDeskDock"
    const val DESCRIPTION = "MobiDesk Screen Receiver Dock"
    const val VERSION = "1.0"
    const val URI = "https://github.com/mobidesk"
    const val SERIAL = "0000000012345678"

    const val AOA_GET_PROTOCOL = 51
    const val AOA_SEND_STRING = 52
    const val AOA_START_ACCESSORY = 53

    val AOA_STRINGS = listOf(
        MANUFACTURER, // 0: Manufacturer
        MODEL,        // 1: Model
        DESCRIPTION,  // 2: Description
        VERSION,      // 3: Version
        URI,          // 4: URI
        SERIAL        // 5: Serial
    )
}
