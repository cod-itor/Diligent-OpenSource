plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_sbcl")
    dynamicDelivery { deliveryType.set("on-demand") }
}
