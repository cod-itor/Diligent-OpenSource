plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_deno")
    dynamicDelivery { deliveryType.set("on-demand") }
}
