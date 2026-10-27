plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_php")
    dynamicDelivery { deliveryType.set("on-demand") }
}
