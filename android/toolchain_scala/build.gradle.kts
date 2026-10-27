plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_scala")
    dynamicDelivery { deliveryType.set("on-demand") }
}
