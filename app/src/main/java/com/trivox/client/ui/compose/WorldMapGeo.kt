package com.trivox.client.ui.compose

import java.util.Locale

internal data class WorldMapPoint(
    val x: Float,
    val y: Float
)

private data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
)

internal object WorldMapGeo {
    fun resolveCode(
        explicitCode: String,
        flagOrName: String
    ): String? {
        val normalized =
            explicitCode
                .trim()
                .uppercase(Locale.ROOT)
                .takeIf {
                    it.length == 2 &&
                        coordinate(it) != null
                }
        return normalized
            ?: codeFromFlag(flagOrName)
                ?.takeIf {
                    coordinate(it) != null
                }
    }

    fun pointFor(code: String?): WorldMapPoint? {
        val normalized =
            code
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?: return null
        val geo =
            coordinate(normalized)
                ?: return null
        return WorldMapPoint(
            x =
                (
                    (geo.longitude + 180.0) /
                        360.0
                    )
                    .coerceIn(0.02, 0.98)
                    .toFloat(),
            y =
                (
                    (90.0 - geo.latitude) /
                        180.0
                    )
                    .coerceIn(0.05, 0.95)
                    .toFloat()
        )
    }

    fun flagFor(code: String?): String {
        val normalized =
            code
                ?.trim()
                ?.uppercase(Locale.ROOT)
                .orEmpty()
        if (
            normalized.length != 2 ||
            normalized.any { it !in 'A'..'Z' }
        ) {
            return ""
        }
        val first =
            Character.toChars(
                REGIONAL_BASE +
                    (normalized[0] - 'A')
            )
        val second =
            Character.toChars(
                REGIONAL_BASE +
                    (normalized[1] - 'A')
            )
        return String(first) + String(second)
    }

    fun codeFromFlag(text: String): String? {
        var index = 0
        while (index < text.length) {
            val first =
                Character.codePointAt(
                    text,
                    index
                )
            val next =
                index +
                    Character.charCount(first)
            if (
                first in REGIONAL_BASE..
                    REGIONAL_LAST &&
                next < text.length
            ) {
                val second =
                    Character.codePointAt(
                        text,
                        next
                    )
                if (
                    second in REGIONAL_BASE..
                        REGIONAL_LAST
                ) {
                    return buildString(2) {
                        append(
                            'A' +
                                (first - REGIONAL_BASE)
                        )
                        append(
                            'A' +
                                (second - REGIONAL_BASE)
                        )
                    }
                }
            }
            index = next
        }
        return null
    }

    private fun coordinate(code: String): GeoCoordinate? =
        when (code) {
            "AD" -> GeoCoordinate(42.55, 1.60)
            "AE" -> GeoCoordinate(24.40, 54.40)
            "AF" -> GeoCoordinate(33.94, 67.71)
            "AG" -> GeoCoordinate(17.06, -61.80)
            "AL" -> GeoCoordinate(41.15, 20.17)
            "AM" -> GeoCoordinate(40.07, 45.04)
            "AO" -> GeoCoordinate(-11.20, 17.87)
            "AR" -> GeoCoordinate(-38.42, -63.62)
            "AT" -> GeoCoordinate(47.52, 14.55)
            "AU" -> GeoCoordinate(-25.27, 133.78)
            "AZ" -> GeoCoordinate(40.14, 47.58)
            "BA" -> GeoCoordinate(43.92, 17.68)
            "BB" -> GeoCoordinate(13.19, -59.54)
            "BD" -> GeoCoordinate(23.68, 90.36)
            "BE" -> GeoCoordinate(50.50, 4.47)
            "BF" -> GeoCoordinate(12.24, -1.56)
            "BG" -> GeoCoordinate(42.73, 25.49)
            "BH" -> GeoCoordinate(26.07, 50.56)
            "BI" -> GeoCoordinate(-3.37, 29.92)
            "BJ" -> GeoCoordinate(9.31, 2.32)
            "BN" -> GeoCoordinate(4.54, 114.73)
            "BO" -> GeoCoordinate(-16.29, -63.59)
            "BR" -> GeoCoordinate(-14.24, -51.93)
            "BS" -> GeoCoordinate(25.03, -77.40)
            "BT" -> GeoCoordinate(27.51, 90.43)
            "BW" -> GeoCoordinate(-22.33, 24.68)
            "BY" -> GeoCoordinate(53.71, 27.95)
            "BZ" -> GeoCoordinate(17.19, -88.50)
            "CA" -> GeoCoordinate(56.13, -106.35)
            "CD" -> GeoCoordinate(-4.04, 21.76)
            "CF" -> GeoCoordinate(6.61, 20.94)
            "CG" -> GeoCoordinate(-0.23, 15.83)
            "CH" -> GeoCoordinate(46.82, 8.23)
            "CI" -> GeoCoordinate(7.54, -5.55)
            "CL" -> GeoCoordinate(-35.68, -71.54)
            "CM" -> GeoCoordinate(7.37, 12.35)
            "CN" -> GeoCoordinate(35.86, 104.20)
            "CO" -> GeoCoordinate(4.57, -74.30)
            "CR" -> GeoCoordinate(9.75, -83.75)
            "CU" -> GeoCoordinate(21.52, -77.78)
            "CV" -> GeoCoordinate(16.00, -24.01)
            "CY" -> GeoCoordinate(35.13, 33.43)
            "CZ" -> GeoCoordinate(49.82, 15.47)
            "DE" -> GeoCoordinate(51.17, 10.45)
            "DJ" -> GeoCoordinate(11.83, 42.59)
            "DK" -> GeoCoordinate(56.26, 9.50)
            "DM" -> GeoCoordinate(15.41, -61.37)
            "DO" -> GeoCoordinate(18.74, -70.16)
            "DZ" -> GeoCoordinate(28.03, 1.66)
            "EC" -> GeoCoordinate(-1.83, -78.18)
            "EE" -> GeoCoordinate(58.60, 25.01)
            "EG" -> GeoCoordinate(26.82, 30.80)
            "ER" -> GeoCoordinate(15.18, 39.78)
            "ES" -> GeoCoordinate(40.46, -3.75)
            "ET" -> GeoCoordinate(9.15, 40.49)
            "FI" -> GeoCoordinate(61.92, 25.75)
            "FJ" -> GeoCoordinate(-17.71, 178.07)
            "FM" -> GeoCoordinate(7.43, 150.55)
            "FR" -> GeoCoordinate(46.23, 2.21)
            "GA" -> GeoCoordinate(-0.80, 11.61)
            "GB" -> GeoCoordinate(55.38, -3.44)
            "GD" -> GeoCoordinate(12.12, -61.68)
            "GE" -> GeoCoordinate(42.32, 43.36)
            "GH" -> GeoCoordinate(7.95, -1.02)
            "GM" -> GeoCoordinate(13.44, -15.31)
            "GN" -> GeoCoordinate(9.95, -9.70)
            "GQ" -> GeoCoordinate(1.65, 10.27)
            "GR" -> GeoCoordinate(39.07, 21.82)
            "GT" -> GeoCoordinate(15.78, -90.23)
            "GW" -> GeoCoordinate(11.80, -15.18)
            "GY" -> GeoCoordinate(4.86, -58.93)
            "HN" -> GeoCoordinate(15.20, -86.24)
            "HR" -> GeoCoordinate(45.10, 15.20)
            "HT" -> GeoCoordinate(18.97, -72.29)
            "HU" -> GeoCoordinate(47.16, 19.50)
            "ID" -> GeoCoordinate(-0.79, 113.92)
            "IE" -> GeoCoordinate(53.14, -7.69)
            "IL" -> GeoCoordinate(31.05, 34.85)
            "IN" -> GeoCoordinate(20.59, 78.96)
            "IQ" -> GeoCoordinate(33.22, 43.68)
            "IR" -> GeoCoordinate(32.43, 53.69)
            "IS" -> GeoCoordinate(64.96, -19.02)
            "IT" -> GeoCoordinate(41.87, 12.57)
            "JM" -> GeoCoordinate(18.11, -77.30)
            "JO" -> GeoCoordinate(30.59, 36.24)
            "JP" -> GeoCoordinate(36.20, 138.25)
            "KE" -> GeoCoordinate(-0.02, 37.91)
            "KG" -> GeoCoordinate(41.20, 74.77)
            "KH" -> GeoCoordinate(12.57, 104.99)
            "KI" -> GeoCoordinate(1.87, -157.36)
            "KM" -> GeoCoordinate(-11.88, 43.87)
            "KN" -> GeoCoordinate(17.36, -62.78)
            "KP" -> GeoCoordinate(40.34, 127.51)
            "KR" -> GeoCoordinate(35.91, 127.77)
            "KW" -> GeoCoordinate(29.31, 47.48)
            "KZ" -> GeoCoordinate(48.02, 66.92)
            "LA" -> GeoCoordinate(19.86, 102.50)
            "LB" -> GeoCoordinate(33.85, 35.86)
            "LC" -> GeoCoordinate(13.91, -60.98)
            "LI" -> GeoCoordinate(47.17, 9.55)
            "LK" -> GeoCoordinate(7.87, 80.77)
            "LR" -> GeoCoordinate(6.43, -9.43)
            "LS" -> GeoCoordinate(-29.61, 28.23)
            "LT" -> GeoCoordinate(55.17, 23.88)
            "LU" -> GeoCoordinate(49.82, 6.13)
            "LV" -> GeoCoordinate(56.88, 24.60)
            "LY" -> GeoCoordinate(26.34, 17.23)
            "MA" -> GeoCoordinate(31.79, -7.09)
            "MC" -> GeoCoordinate(43.74, 7.42)
            "MD" -> GeoCoordinate(47.41, 28.37)
            "ME" -> GeoCoordinate(42.71, 19.37)
            "MG" -> GeoCoordinate(-18.77, 46.87)
            "MH" -> GeoCoordinate(7.13, 171.18)
            "MK" -> GeoCoordinate(41.61, 21.75)
            "ML" -> GeoCoordinate(17.57, -4.00)
            "MM" -> GeoCoordinate(21.91, 95.96)
            "MN" -> GeoCoordinate(46.86, 103.85)
            "MR" -> GeoCoordinate(21.01, -10.94)
            "MT" -> GeoCoordinate(35.94, 14.38)
            "MU" -> GeoCoordinate(-20.35, 57.55)
            "MV" -> GeoCoordinate(3.20, 73.22)
            "MW" -> GeoCoordinate(-13.25, 34.30)
            "MX" -> GeoCoordinate(23.63, -102.55)
            "MY" -> GeoCoordinate(4.21, 101.98)
            "MZ" -> GeoCoordinate(-18.67, 35.53)
            "NA" -> GeoCoordinate(-22.96, 18.49)
            "NE" -> GeoCoordinate(17.61, 8.08)
            "NG" -> GeoCoordinate(9.08, 8.68)
            "NI" -> GeoCoordinate(12.87, -85.21)
            "NL" -> GeoCoordinate(52.13, 5.29)
            "NO" -> GeoCoordinate(60.47, 8.47)
            "NP" -> GeoCoordinate(28.39, 84.12)
            "NR" -> GeoCoordinate(-0.52, 166.93)
            "NZ" -> GeoCoordinate(-40.90, 174.89)
            "OM" -> GeoCoordinate(21.51, 55.92)
            "PA" -> GeoCoordinate(8.54, -80.78)
            "PE" -> GeoCoordinate(-9.19, -75.02)
            "PG" -> GeoCoordinate(-6.31, 143.96)
            "PH" -> GeoCoordinate(12.88, 121.77)
            "PK" -> GeoCoordinate(30.38, 69.35)
            "PL" -> GeoCoordinate(51.92, 19.15)
            "PS" -> GeoCoordinate(31.95, 35.23)
            "PT" -> GeoCoordinate(39.40, -8.22)
            "PW" -> GeoCoordinate(7.51, 134.58)
            "PY" -> GeoCoordinate(-23.44, -58.44)
            "QA" -> GeoCoordinate(25.35, 51.18)
            "RO" -> GeoCoordinate(45.94, 24.97)
            "RS" -> GeoCoordinate(44.02, 21.01)
            "RU" -> GeoCoordinate(61.52, 105.32)
            "RW" -> GeoCoordinate(-1.94, 29.87)
            "SA" -> GeoCoordinate(23.89, 45.08)
            "SB" -> GeoCoordinate(-9.65, 160.16)
            "SC" -> GeoCoordinate(-4.68, 55.49)
            "SD" -> GeoCoordinate(12.86, 30.22)
            "SE" -> GeoCoordinate(60.13, 18.64)
            "SG" -> GeoCoordinate(1.35, 103.82)
            "SI" -> GeoCoordinate(46.15, 14.99)
            "SK" -> GeoCoordinate(48.67, 19.70)
            "SL" -> GeoCoordinate(8.46, -11.78)
            "SM" -> GeoCoordinate(43.94, 12.46)
            "SN" -> GeoCoordinate(14.50, -14.45)
            "SO" -> GeoCoordinate(5.15, 46.20)
            "SR" -> GeoCoordinate(3.92, -56.03)
            "SS" -> GeoCoordinate(6.88, 31.31)
            "ST" -> GeoCoordinate(0.19, 6.61)
            "SV" -> GeoCoordinate(13.79, -88.90)
            "SY" -> GeoCoordinate(34.80, 38.99)
            "SZ" -> GeoCoordinate(-26.52, 31.47)
            "TD" -> GeoCoordinate(15.45, 18.73)
            "TG" -> GeoCoordinate(8.62, 0.82)
            "TH" -> GeoCoordinate(15.87, 100.99)
            "TJ" -> GeoCoordinate(38.86, 71.28)
            "TL" -> GeoCoordinate(-8.87, 125.73)
            "TM" -> GeoCoordinate(38.97, 59.56)
            "TN" -> GeoCoordinate(33.89, 9.54)
            "TO" -> GeoCoordinate(-21.18, -175.20)
            "TR" -> GeoCoordinate(38.96, 35.24)
            "TT" -> GeoCoordinate(10.69, -61.22)
            "TV" -> GeoCoordinate(-7.11, 177.65)
            "TW" -> GeoCoordinate(23.70, 121.00)
            "TZ" -> GeoCoordinate(-6.37, 34.89)
            "UA" -> GeoCoordinate(48.38, 31.17)
            "UG" -> GeoCoordinate(1.37, 32.29)
            "US" -> GeoCoordinate(37.09, -95.71)
            "UY" -> GeoCoordinate(-32.52, -55.77)
            "UZ" -> GeoCoordinate(41.38, 64.59)
            "VA" -> GeoCoordinate(41.90, 12.45)
            "VC" -> GeoCoordinate(12.98, -61.29)
            "VE" -> GeoCoordinate(6.42, -66.59)
            "VN" -> GeoCoordinate(14.06, 108.28)
            "VU" -> GeoCoordinate(-15.38, 166.96)
            "WS" -> GeoCoordinate(-13.76, -172.10)
            "XK" -> GeoCoordinate(42.60, 20.90)
            "YE" -> GeoCoordinate(15.55, 48.52)
            "ZA" -> GeoCoordinate(-30.56, 22.94)
            "ZM" -> GeoCoordinate(-13.13, 27.85)
            "ZW" -> GeoCoordinate(-19.02, 29.15)
            "AX" -> GeoCoordinate(60.18, 19.92)
            "AI" -> GeoCoordinate(18.22, -63.07)
            "AS" -> GeoCoordinate(-14.27, -170.13)
            "AW" -> GeoCoordinate(12.52, -69.97)
            "BM" -> GeoCoordinate(32.30, -64.75)
            "BQ" -> GeoCoordinate(12.18, -68.24)
            "CC" -> GeoCoordinate(-12.16, 96.87)
            "CK" -> GeoCoordinate(-21.24, -159.77)
            "CW" -> GeoCoordinate(12.17, -68.99)
            "CX" -> GeoCoordinate(-10.45, 105.69)
            "EH" -> GeoCoordinate(24.20, -12.90)
            "FK" -> GeoCoordinate(-51.80, -59.00)
            "FO" -> GeoCoordinate(61.90, -6.90)
            "GF" -> GeoCoordinate(3.93, -53.13)
            "GG" -> GeoCoordinate(49.45, -2.58)
            "GI" -> GeoCoordinate(36.14, -5.35)
            "GL" -> GeoCoordinate(71.70, -42.60)
            "GP" -> GeoCoordinate(16.27, -61.55)
            "GU" -> GeoCoordinate(13.44, 144.79)
            "HK" -> GeoCoordinate(22.32, 114.17)
            "IM" -> GeoCoordinate(54.24, -4.55)
            "IO" -> GeoCoordinate(-6.34, 71.88)
            "JE" -> GeoCoordinate(49.21, -2.13)
            "KY" -> GeoCoordinate(19.31, -81.25)
            "MF" -> GeoCoordinate(18.07, -63.05)
            "MO" -> GeoCoordinate(22.20, 113.54)
            "MP" -> GeoCoordinate(15.10, 145.70)
            "MQ" -> GeoCoordinate(14.64, -61.02)
            "MS" -> GeoCoordinate(16.74, -62.19)
            "NC" -> GeoCoordinate(-20.90, 165.60)
            "NF" -> GeoCoordinate(-29.04, 167.95)
            "NU" -> GeoCoordinate(-19.05, -169.87)
            "PF" -> GeoCoordinate(-17.68, -149.40)
            "PM" -> GeoCoordinate(46.94, -56.33)
            "PR" -> GeoCoordinate(18.22, -66.59)
            "RE" -> GeoCoordinate(-21.12, 55.54)
            "SH" -> GeoCoordinate(-15.97, -5.70)
            "SJ" -> GeoCoordinate(77.55, 23.67)
            "SX" -> GeoCoordinate(18.04, -63.05)
            "TC" -> GeoCoordinate(21.69, -71.80)
            "TK" -> GeoCoordinate(-9.20, -171.85)
            "VG" -> GeoCoordinate(18.42, -64.64)
            "VI" -> GeoCoordinate(18.34, -64.90)
            "WF" -> GeoCoordinate(-13.77, -177.16)
            "YT" -> GeoCoordinate(-12.83, 45.17)
            else -> null
        }

    private const val REGIONAL_BASE = 0x1F1E6
    private const val REGIONAL_LAST = 0x1F1FF
}
