package tachiyomi.domain.download.service

/** Target screen for re-encoded downloads: pages are scaled down to fit [width]x[height]. */
enum class DeviceProfile(val width: Int, val height: Int) {
    MUSNAP_OCEAN_C(1264, 1680),
    BOOX_PAGE(1264, 1680),
}
