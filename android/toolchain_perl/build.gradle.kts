plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_perl")
    dynamicDelivery { deliveryType.set("on-demand") }
}
