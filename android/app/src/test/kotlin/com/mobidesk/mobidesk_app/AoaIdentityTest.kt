package com.mobidesk.mobidesk_app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AoaIdentityTest {

    private fun findWorkspaceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
            File(System.getProperty("user.dir"), relativePath),
            File(System.getProperty("user.dir"), "../$relativePath")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find file '$relativePath' in candidates: $candidates")
    }

    @Test
    fun testAoaConstantsMatchSingleSourceOfTruthJson() {
        val jsonFile = findWorkspaceFile("aoa_identity.json")
        val json = JSONObject(jsonFile.readText())

        val manufacturer = json.getString("manufacturer")
        val model = json.getString("model")
        val description = json.getString("description")
        val version = json.getString("version")
        val uri = json.getString("uri")
        val serial = json.getString("serial")

        // Assert Kotlin AoaConstants exactly matches single source of truth JSON
        assertEquals("Manufacturer mismatch", manufacturer, AoaConstants.MANUFACTURER)
        assertEquals("Model mismatch", model, AoaConstants.MODEL)
        assertEquals("Description mismatch", description, AoaConstants.DESCRIPTION)
        assertEquals("Version mismatch", version, AoaConstants.VERSION)
        assertEquals("URI mismatch", uri, AoaConstants.URI)
        assertEquals("Serial mismatch", serial, AoaConstants.SERIAL)

        // Assert UsbHostReceiver constants match AoaConstants
        assertEquals(AoaConstants.MANUFACTURER, UsbHostReceiver.MANUFACTURER)
        assertEquals(AoaConstants.MODEL, UsbHostReceiver.MODEL)
        assertEquals(AoaConstants.DESCRIPTION, UsbHostReceiver.DESCRIPTION)
        assertEquals(AoaConstants.VERSION, UsbHostReceiver.VERSION)
        assertEquals(AoaConstants.URI, UsbHostReceiver.URI)
        assertEquals(AoaConstants.SERIAL, UsbHostReceiver.SERIAL)

        // Assert AOA_STRINGS list order
        assertEquals(6, AoaConstants.AOA_STRINGS.size)
        assertEquals(manufacturer, AoaConstants.AOA_STRINGS[0])
        assertEquals(model, AoaConstants.AOA_STRINGS[1])
        assertEquals(description, AoaConstants.AOA_STRINGS[2])
        assertEquals(version, AoaConstants.AOA_STRINGS[3])
        assertEquals(uri, AoaConstants.AOA_STRINGS[4])
        assertEquals(serial, AoaConstants.AOA_STRINGS[5])
    }

    @Test
    fun testAccessoryFilterXmlMatchesAoaConstants() {
        val xmlFile = findWorkspaceFile("android/app/src/main/res/xml/accessory_filter.xml")
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(xmlFile)
        val nodes = doc.getElementsByTagName("usb-accessory")

        assertTrue("Expected at least one <usb-accessory> node", nodes.length > 0)
        val node = nodes.item(0)
        val attrs = node.attributes

        val xmlManufacturer = attrs.getNamedItem("manufacturer")?.nodeValue
        val xmlModel = attrs.getNamedItem("model")?.nodeValue
        val xmlVersion = attrs.getNamedItem("version")?.nodeValue

        assertNotNull("manufacturer attribute missing in accessory_filter.xml", xmlManufacturer)
        assertNotNull("model attribute missing in accessory_filter.xml", xmlModel)
        assertNotNull("version attribute missing in accessory_filter.xml", xmlVersion)

        assertEquals("accessory_filter.xml manufacturer mismatch", AoaConstants.MANUFACTURER, xmlManufacturer)
        assertEquals("accessory_filter.xml model mismatch", AoaConstants.MODEL, xmlModel)
        assertEquals("accessory_filter.xml version mismatch", AoaConstants.VERSION, xmlVersion)
    }
}
