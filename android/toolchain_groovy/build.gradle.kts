plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_groovy")
    dynamicDelivery { deliveryType.set("on-demand") }
}
