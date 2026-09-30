package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons `design/Android-App.pen` uses, as the design names them: Lucide (24-unit, 2-unit
 * round stroke; ISC) and Phosphor (256-unit fills; MIT). Generated from lucide-static 1.49.0 and
 * @phosphor-icons/core 2.1.1 — each path is the SVG's own, so the glyphs match the frames.
 * Draw them with [de.corespace.shroud.ui.components.ShroudIcon], which tints them.
 */
object ShroudIcons {
    val ArrowRight: ImageVector by lazy {
        icon("ArrowRight", 24f, true,
        "M5 12h14",
        "m12 5 7 7-7 7")
    }
    val AtSign: ImageVector by lazy {
        icon("AtSign", 24f, true,
        "M8.0,12.0a4.0,4.0 0 1,0 8.0,0a4.0,4.0 0 1,0 -8.0,0",
        "M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8")
    }
    val AudioLines: ImageVector by lazy {
        icon("AudioLines", 24f, true,
        "M2 10v3",
        "M6 6v11",
        "M10 3v18",
        "M14 8v7",
        "M18 5v13",
        "M22 10v3")
    }
    val Check: ImageVector by lazy {
        icon("Check", 24f, true,
        "M20 6 9 17l-5-5")
    }
    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft", 24f, true,
        "m15 18-6-6 6-6")
    }
    val CircleCheck: ImageVector by lazy {
        icon("CircleCheck", 24f, true,
        "M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0",
        "m16 9-5.5 5.5L8 12")
    }
    val Circle: ImageVector by lazy {
        icon("Circle", 24f, true,
        "M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0")
    }
    val Clipboard: ImageVector by lazy {
        icon("Clipboard", 24f, true,
        "M9.0,2.0h6.0a1.0,1.0 0 0 1 1.0,1.0v2.0a1.0,1.0 0 0 1 -1.0,1.0h-6.0a1.0,1.0 0 0 1 -1.0,-1.0v-2.0a1.0,1.0 0 0 1 1.0,-1.0z",
        "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2")
    }
    val EyeOff: ImageVector by lazy {
        icon("EyeOff", 24f, true,
        "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
        "M14.084 14.158a3 3 0 0 1-4.242-4.242",
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
        "m2 2 20 20")
    }
    val Eye: ImageVector by lazy {
        icon("Eye", 24f, true,
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        "M9.0,12.0a3.0,3.0 0 1,0 6.0,0a3.0,3.0 0 1,0 -6.0,0")
    }
    val Folder: ImageVector by lazy {
        icon("Folder", 24f, true,
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }
    val Globe: ImageVector by lazy {
        icon("Globe", 24f, true,
        "M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20")
    }
    val Hash: ImageVector by lazy {
        icon("Hash", 24f, true,
        "M4.0,9.0L20.0,9.0",
        "M4.0,15.0L20.0,15.0",
        "M10.0,3.0L8.0,21.0",
        "M16.0,3.0L14.0,21.0")
    }
    val Info: ImageVector by lazy {
        icon("Info", 24f, true,
        "M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0",
        "M12 16v-4",
        "M12 8h.01")
    }
    val KeyRound: ImageVector by lazy {
        icon("KeyRound", 24f, true,
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        "M16.0,7.5a0.5,0.5 0 1,0 1.0,0a0.5,0.5 0 1,0 -1.0,0")
    }
    val Link: ImageVector by lazy {
        icon("Link", 24f, true,
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    }
    val LockKeyhole: ImageVector by lazy {
        icon("LockKeyhole", 24f, true,
        "M11.0,16.0a1.0,1.0 0 1,0 2.0,0a1.0,1.0 0 1,0 -2.0,0",
        "M5.0,10.0h14.0a2.0,2.0 0 0 1 2.0,2.0v8.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-8.0a2.0,2.0 0 0 1 2.0,-2.0z",
        "M7 10V7a5 5 0 0 1 10 0v3")
    }
    val Lock: ImageVector by lazy {
        icon("Lock", 24f, true,
        "M5.0,11.0h14.0a2.0,2.0 0 0 1 2.0,2.0v7.0a2.0,2.0 0 0 1 -2.0,2.0h-14.0a2.0,2.0 0 0 1 -2.0,-2.0v-7.0a2.0,2.0 0 0 1 2.0,-2.0z",
        "M7 11V7a5 5 0 0 1 10 0v4")
    }
    val ShieldAlert: ImageVector by lazy {
        icon("ShieldAlert", 24f, true,
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "M12 8v4",
        "M12 16h.01")
    }
    val ShieldCheck: ImageVector by lazy {
        icon("ShieldCheck", 24f, true,
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4")
    }
    val ShieldHalf: ImageVector by lazy {
        icon("ShieldHalf", 24f, true,
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "M12 22V2")
    }
    val Shield: ImageVector by lazy {
        icon("Shield", 24f, true,
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
    }
    val TriangleAlert: ImageVector by lazy {
        icon("TriangleAlert", 24f, true,
        "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h.01")
    }
    val ArrowRightBold: ImageVector by lazy {
        icon("ArrowRightBold", 256f, false,
        "M224.49,136.49l-72,72a12,12,0,0,1-17-17L187,140H40a12,12,0,0,1,0-24H187L135.51,64.48a12,12,0,0,1,17-17l72,72A12,12,0,0,1,224.49,136.49Z")
    }
    val CaretLeftBold: ImageVector by lazy {
        icon("CaretLeftBold", 256f, false,
        "M168.49,199.51a12,12,0,0,1-17,17l-80-80a12,12,0,0,1,0-17l80-80a12,12,0,0,1,17,17L97,128Z")
    }
    val CheckCircleFill: ImageVector by lazy {
        icon("CheckCircleFill", 256f, false,
        "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm45.66,85.66-56,56a8,8,0,0,1-11.32,0l-24-24a8,8,0,0,1,11.32-11.32L112,148.69l50.34-50.35a8,8,0,0,1,11.32,11.32Z")
    }
    val Fingerprint: ImageVector by lazy {
        icon("Fingerprint", 256f, false,
        "M72,128a134.63,134.63,0,0,1-14.16,60.47,8,8,0,1,1-14.32-7.12A118.8,118.8,0,0,0,56,128,71.73,71.73,0,0,1,83,71.8,8,8,0,1,1,93,84.29,55.76,55.76,0,0,0,72,128Zm56-8a8,8,0,0,0-8,8,184.12,184.12,0,0,1-23,89.1,8,8,0,0,0,14,7.76A200.19,200.19,0,0,0,136,128,8,8,0,0,0,128,120Zm0-32a40,40,0,0,0-40,40,8,8,0,0,0,16,0,24,24,0,0,1,48,0,214.09,214.09,0,0,1-20.51,92A8,8,0,1,0,146,226.83,230,230,0,0,0,168,128,40,40,0,0,0,128,88Zm0-64A104.11,104.11,0,0,0,24,128a87.76,87.76,0,0,1-5,29.33,8,8,0,0,0,15.09,5.33A103.9,103.9,0,0,0,40,128a88,88,0,0,1,176,0,282.24,282.24,0,0,1-5.29,54.45,8,8,0,0,0,6.3,9.4,8.22,8.22,0,0,0,1.55.15,8,8,0,0,0,7.84-6.45A298.37,298.37,0,0,0,232,128,104.12,104.12,0,0,0,128,24ZM94.4,152.17A8,8,0,0,0,85,158.42a151,151,0,0,1-17.21,45.44,8,8,0,0,0,13.86,8,166.67,166.67,0,0,0,19-50.25A8,8,0,0,0,94.4,152.17ZM128,56a72.85,72.85,0,0,0-9,.56,8,8,0,0,0,2,15.87A56.08,56.08,0,0,1,184,128a252.12,252.12,0,0,1-1.92,31A8,8,0,0,0,189,168a8.39,8.39,0,0,0,1,.06,8,8,0,0,0,7.92-7,266.48,266.48,0,0,0,2-33A72.08,72.08,0,0,0,128,56Zm57.93,128.25a8,8,0,0,0-9.75,5.75c-1.46,5.69-3.15,11.4-5,17a8,8,0,0,0,5,10.13,7.88,7.88,0,0,0,2.55.42,8,8,0,0,0,7.58-5.46c2-5.92,3.79-12,5.35-18.05A8,8,0,0,0,185.94,184.26Z")
    }
    val GearSixFill: ImageVector by lazy {
        icon("GearSixFill", 256f, false,
        "M237.94,107.21a8,8,0,0,0-3.89-5.4l-29.83-17-.12-33.62a8,8,0,0,0-2.83-6.08,111.91,111.91,0,0,0-36.72-20.67,8,8,0,0,0-6.46.59L128,41.85,97.88,25a8,8,0,0,0-6.47-.6A111.92,111.92,0,0,0,54.73,45.15a8,8,0,0,0-2.83,6.07l-.15,33.65-29.83,17a8,8,0,0,0-3.89,5.4,106.47,106.47,0,0,0,0,41.56,8,8,0,0,0,3.89,5.4l29.83,17,.12,33.63a8,8,0,0,0,2.83,6.08,111.91,111.91,0,0,0,36.72,20.67,8,8,0,0,0,6.46-.59L128,214.15,158.12,231a7.91,7.91,0,0,0,3.9,1,8.09,8.09,0,0,0,2.57-.42,112.1,112.1,0,0,0,36.68-20.73,8,8,0,0,0,2.83-6.07l.15-33.65,29.83-17a8,8,0,0,0,3.89-5.4A106.47,106.47,0,0,0,237.94,107.21ZM128,168a40,40,0,1,1,40-40A40,40,0,0,1,128,168Z")
    }
    val HardDriveFill: ImageVector by lazy {
        icon("HardDriveFill", 256f, false,
        "M224,64H32A16,16,0,0,0,16,80v96a16,16,0,0,0,16,16H224a16,16,0,0,0,16-16V80A16,16,0,0,0,224,64Zm-36,76a12,12,0,1,1,12-12A12,12,0,0,1,188,140Z")
    }
    val HardDrivesFill: ImageVector by lazy {
        icon("HardDrivesFill", 256f, false,
        "M208,40H48A16,16,0,0,0,32,56v48a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V56A16,16,0,0,0,208,40ZM180,92a12,12,0,1,1,12-12A12,12,0,0,1,180,92Z",
        "M208,136H48a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V152A16,16,0,0,0,208,136Zm-28,52a12,12,0,1,1,12-12A12,12,0,0,1,180,188Z")
    }
    val InfoFill: ImageVector by lazy {
        icon("InfoFill", 256f, false,
        "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm-4,48a12,12,0,1,1-12,12A12,12,0,0,1,124,72Zm12,112a16,16,0,0,1-16-16V128a8,8,0,0,1,0-16,16,16,0,0,1,16,16v40a8,8,0,0,1,0,16Z")
    }
    val KeyFill: ImageVector by lazy {
        icon("KeyFill", 256f, false,
        "M216.57,39.43A80,80,0,0,0,83.91,120.78L28.69,176A15.86,15.86,0,0,0,24,187.31V216a16,16,0,0,0,16,16H72a8,8,0,0,0,8-8V208H96a8,8,0,0,0,8-8V184h16a8,8,0,0,0,5.66-2.34l9.56-9.57A79.73,79.73,0,0,0,160,176h.1A80,80,0,0,0,216.57,39.43ZM180,92a16,16,0,1,1,16-16A16,16,0,0,1,180,92Z")
    }
    val LockFill: ImageVector by lazy {
        icon("LockFill", 256f, false,
        "M208,80H176V56a48,48,0,0,0-96,0V80H48A16,16,0,0,0,32,96V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V96A16,16,0,0,0,208,80Zm-80,84a12,12,0,1,1,12-12A12,12,0,0,1,128,164Zm32-84H96V56a32,32,0,0,1,64,0Z")
    }
    val PhoneFill: ImageVector by lazy {
        icon("PhoneFill", 256f, false,
        "M231.88,175.08A56.26,56.26,0,0,1,176,224C96.6,224,32,159.4,32,80A56.26,56.26,0,0,1,80.92,24.12a16,16,0,0,1,16.62,9.52l21.12,47.15,0,.12A16,16,0,0,1,117.39,96c-.18.27-.37.52-.57.77L96,121.45c7.49,15.22,23.41,31,38.83,38.51l24.34-20.71a8.12,8.12,0,0,1,.75-.56,16,16,0,0,1,15.17-1.4l.13.06,47.11,21.11A16,16,0,0,1,231.88,175.08Z")
    }
    val SealCheckFill: ImageVector by lazy {
        icon("SealCheckFill", 256f, false,
        "M225.86,102.82c-3.77-3.94-7.67-8-9.14-11.57-1.36-3.27-1.44-8.69-1.52-13.94-.15-9.76-.31-20.82-8-28.51s-18.75-7.85-28.51-8c-5.25-.08-10.67-.16-13.94-1.52-3.56-1.47-7.63-5.37-11.57-9.14C146.28,23.51,138.44,16,128,16s-18.27,7.51-25.18,14.14c-3.94,3.77-8,7.67-11.57,9.14C88,40.64,82.56,40.72,77.31,40.8c-9.76.15-20.82.31-28.51,8S41,67.55,40.8,77.31c-.08,5.25-.16,10.67-1.52,13.94-1.47,3.56-5.37,7.63-9.14,11.57C23.51,109.72,16,117.56,16,128s7.51,18.27,14.14,25.18c3.77,3.94,7.67,8,9.14,11.57,1.36,3.27,1.44,8.69,1.52,13.94.15,9.76.31,20.82,8,28.51s18.75,7.85,28.51,8c5.25.08,10.67.16,13.94,1.52,3.56,1.47,7.63,5.37,11.57,9.14C109.72,232.49,117.56,240,128,240s18.27-7.51,25.18-14.14c3.94-3.77,8-7.67,11.57-9.14,3.27-1.36,8.69-1.44,13.94-1.52,9.76-.15,20.82-.31,28.51-8s7.85-18.75,8-28.51c.08-5.25.16-10.67,1.52-13.94,1.47-3.56,5.37-7.63,9.14-11.57C232.49,146.28,240,138.44,240,128S232.49,109.73,225.86,102.82Zm-52.2,6.84-56,56a8,8,0,0,1-11.32,0l-24-24a8,8,0,0,1,11.32-11.32L112,148.69l50.34-50.35a8,8,0,0,1,11.32,11.32Z")
    }
    val WarningCircleFill: ImageVector by lazy {
        icon("WarningCircleFill", 256f, false,
        "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm-8,56a8,8,0,0,1,16,0v56a8,8,0,0,1-16,0Zm8,104a12,12,0,1,1,12-12A12,12,0,0,1,128,184Z")
    }
    val WarningFill: ImageVector by lazy {
        icon("WarningFill", 256f, false,
        "M236.8,188.09,149.35,36.22h0a24.76,24.76,0,0,0-42.7,0L19.2,188.09a23.51,23.51,0,0,0,0,23.72A24.35,24.35,0,0,0,40.55,224h174.9a24.35,24.35,0,0,0,21.33-12.19A23.51,23.51,0,0,0,236.8,188.09ZM120,104a8,8,0,0,1,16,0v40a8,8,0,0,1-16,0Zm8,88a12,12,0,1,1,12-12A12,12,0,0,1,128,192Z")
    }
}

private fun icon(name: String, viewport: Float, stroked: Boolean, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        for (data in paths) {
            if (stroked) {
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
            }
        }
    }.build()
