package com.kaelixbox.container

/**
 * 默认容器镜像下载配置。
 *
 * 镜像托管在本项目 GitHub Releases，禁止提交进 git 源码仓库。
 * 下载时先尝试加速镜像地址（默认 aka.ms），失败后回退原始 GitHub Release 直链。
 */
object ImageConfig {

    const val IMAGE_FILENAME = "debian-trixie-arm64-minimal.tar.zst"

    /** aka.ms 加速节点（默认镜像地址）。 */
    const val AKAMS_MIRROR_URL = "https://aka.ms/kaelix-debian13"

    /** 原始 GitHub Release 直链（备选）。 */
    const val GITHUB_RELEASE_URL =
        "https://github.com/LEGNiX-zch/Kaelix-Box/releases/download/v0.1/$IMAGE_FILENAME"

    /**
     * 预期 SHA256 校验值。留空则跳过校验。
     * 发布 Release 后请更新此值为实际文件的 sha256sum。
     */
    const val EXPECTED_SHA256 = ""

    /**
     * 返回生效的加速镜像地址：用户自定义 > aka.ms 默认。
     */
    fun mirrorUrl(customMirror: String): String =
        if (customMirror.isNotBlank()) customMirror else AKAMS_MIRROR_URL
}
