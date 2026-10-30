package com.aditya.album

import com.aditya.album.CodeContent.Contact
import com.aditya.album.CodeContent.Email
import com.aditya.album.CodeContent.Geo
import com.aditya.album.CodeContent.Link
import com.aditya.album.CodeContent.Phone
import com.aditya.album.CodeContent.Plain
import com.aditya.album.CodeContent.Sms
import com.aditya.album.CodeContent.Upi
import com.aditya.album.CodeContent.Wifi
import org.junit.Assert.assertEquals
import org.junit.Test

class CodesTest {
    @Test fun webAddressesAreLinks() {
        assertEquals(Link("https://example.com/menu?table=4"), contentOf("https://example.com/menu?table=4"))
        assertEquals(Link("HTTP://EXAMPLE.COM"), contentOf("  HTTP://EXAMPLE.COM \n"))
        assertEquals(Link("https://www.irctc.co.in"), contentOf("www.irctc.co.in"))
        // Nothing but http and https ever opens a browser
        assertEquals(Plain("javascript:alert(1)"), contentOf("javascript:alert(1)"))
        assertEquals(Plain("https://"), contentOf("https://"))
        assertEquals(Plain("https://bad link"), contentOf("https://bad link"))
    }

    @Test fun upiPaymentsKeepTheirWholeRequest() {
        val code = "upi://pay?pa=chaiwala@okaxis&pn=Ravi%20Tea%20Stall&am=40.00&cu=INR&tn=Tea"
        assertEquals(Upi(code, "chaiwala@okaxis", "Ravi Tea Stall", "40.00", "Tea"), contentOf(code))
        assertEquals(Upi("UPI://pay?pa=shop@ybl", "shop@ybl", null, null, null), contentOf("UPI://pay?pa=shop@ybl"))
        // No UPI ID to pay: only text
        assertEquals(Plain("upi://pay?pn=Shop"), contentOf("upi://pay?pn=Shop"))
    }

    @Test fun wifiCodesReadTheirFieldsAndEscapes() {
        assertEquals(Wifi("Home 5G", "pass;word:1", "WPA", hidden = false), contentOf("""WIFI:T:WPA;S:Home 5G;P:pass\;word\:1;;"""))
        assertEquals(Wifi("Cafe", null, null, hidden = true), contentOf("WIFI:S:Cafe;T:nopass;H:true;;"))
        assertEquals(Wifi("Office", "secret123", "SAE"), contentOf("wifi:T:SAE;S:Office;P:secret123;;"))
        assertEquals(Plain("WIFI:T:WPA;P:x;;"), contentOf("WIFI:T:WPA;P:x;;")) // no network name
    }

    @Test fun phoneMailAndMessageSchemes() {
        assertEquals(Phone("+919876543210"), contentOf("tel:+919876543210"))
        assertEquals(Phone("+91 98765 43210"), contentOf("tel:%2B91%2098765%2043210"))
        assertEquals(Email("help@example.in", "Order 12", "Hi there"), contentOf("mailto:help@example.in?subject=Order%2012&body=Hi%20there"))
        assertEquals(Email("help@example.in", "Refund", null), contentOf("MATMSG:TO:help@example.in;SUB:Refund;BODY:;;"))
        assertEquals(Email("ravi@example.com"), contentOf("ravi@example.com"))
        assertEquals(Sms("+919876543210", "Book table"), contentOf("SMSTO:+919876543210:Book table"))
        assertEquals(Sms("9876543210", "Hi"), contentOf("sms:9876543210?body=Hi"))
        assertEquals(Geo("geo:12.9716,77.5946", 12.9716, 77.5946), contentOf("geo:12.9716,77.5946"))
        assertEquals(Plain("geo:200,1"), contentOf("geo:200,1"))
    }

    @Test fun contactCards() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Kumar;Ravi;;;\r\nTEL;TYPE=CELL:+91 98765 43210\r\nEMAIL:ravi@example.com\r\nEND:VCARD"
        assertEquals(Contact("Ravi Kumar", listOf("+91 98765 43210"), listOf("ravi@example.com")), contentOf(vcard))
        assertEquals(Contact("Asha Rao", listOf("080 2345 6789"), emptyList()), contentOf("BEGIN:VCARD\nFN:Asha Rao\nTEL:080 2345 6789\nEND:VCARD"))
        assertEquals(Contact("Ravi Kumar", listOf("+919876543210"), emptyList()), contentOf("MECARD:N:Kumar,Ravi;TEL:+919876543210;;"))
    }

    @Test fun shopBarcodesAndEverythingElseStayText() {
        assertEquals(Plain("8901234567890", product = true), contentOf("8901234567890", product = true))
        assertEquals(Plain("Table 4, Ground floor"), contentOf("Table 4, Ground floor"))
    }

    @Test fun linksInAPhotosWordsComeInReadingOrder() {
        val text = "Visit www.tajhotels.com or swiggy.com/menu.\nCall 98765 43210 or +91 80 2345 6789\nMail bookings@taj.co.in"
        assertEquals(
            listOf(
                Link("https://www.tajhotels.com"),
                Link("https://swiggy.com/menu"),
                Phone("98765 43210"),
                Phone("+91 80 2345 6789"),
                Email("bookings@taj.co.in"),
            ),
            linksIn(text),
        )
    }

    @Test fun digitRunsThatArentPhoneNumbersStayText() {
        // A date, an amount, an Aadhaar-like number, a card-like number, a 10-digit number not starting 6 to 9
        assertEquals(emptyList<CodeContent>(), linksIn("24-10-2026  ₹1,25,000  1234 5678 9012  4111 1111 1111 1111  1234567890"))
        // Toll-free and STD numbers dial; two numbers on separate lines stay two
        assertEquals(listOf(Phone("1800-123-4567"), Phone("(080) 2345 6789")), linksIn("1800-123-4567\n(080) 2345 6789"))
        // File names and abbreviations aren't web addresses
        assertEquals(emptyList<CodeContent>(), linksIn("report.pdf No.1 e.g. Mr.X"))
    }

    @Test fun chipsShowShortLabels() {
        assertEquals("example.com/menu", chipLabel(Link("https://example.com/menu/")))
        assertEquals("example.com/a-very-long-path-th…", chipLabel(Link("https://example.com/a-very-long-path-that-goes-on")))
        assertEquals("+91 98765 43210", chipLabel(Phone("+91 98765 43210")))
    }

    @Test fun theBadgeSaysWhatOneCodeIsOrHowMany() {
        assertEquals("Wi-Fi", codesBadge(listOf(Wifi("Home", null, null))))
        assertEquals("QR code", codesBadge(listOf(Plain("hello"))))
        assertEquals("Barcode", codesBadge(listOf(Plain("890", product = true))))
        assertEquals("2 codes", codesBadge(listOf(Plain("a"), Link("https://b.in"))))
    }
}
