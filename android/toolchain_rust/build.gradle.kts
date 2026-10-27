plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_rust")
    dynamicDelivery { deliveryType.set("on-demand") }
}
