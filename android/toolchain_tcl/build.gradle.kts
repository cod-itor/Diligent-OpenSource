plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_tcl")
    dynamicDelivery { deliveryType.set("on-demand") }
}
