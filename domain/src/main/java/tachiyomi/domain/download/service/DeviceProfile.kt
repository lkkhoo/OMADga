package tachiyomi.domain.download.service

/**
 * Target screen for re-encoded downloads: pages are scaled down to fit [width]x[height], and a [grayscale]
 * panel gets grayscale pages regardless of the grayscale preference.
 */
enum class DeviceProfile(val width: Int, val height: Int, val grayscale: Boolean) {
    MUSNAP_OCEAN_C(1264, 1680, grayscale = false),
    BOOX_PAGE(1264, 1680, grayscale = true),
}
