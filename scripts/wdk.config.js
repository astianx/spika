// Wallet networks shipped in the Android v0.1 app.
module.exports = {
  transport: 'jsonrpc',
  networks: {
    base: { package: '@tetherto/wdk-wallet-evm' },
    'base-gasless': { package: '@tetherto/wdk-wallet-evm-7702-gasless' }
  },
  protocols: {},
  output: {
    bundle: './.wdk-bundle/wdk-worklet.bundle',
    addons: { android: './android-addons' }
  },
  options: {
    linkAddons: true,
    platforms: ['android'],
    targets: ['android-arm64', 'android-arm', 'android-ia32', 'android-x64'],
    convertEsmToCjs: false
  }
}
