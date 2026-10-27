plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_kotlin")
    dynamicDelivery { deliveryType.set("on-demand") }
}
