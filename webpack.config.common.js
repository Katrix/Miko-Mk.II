const Path = require('path')
const VueLoaderPlugin = require('vue-loader/lib/plugin')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
// const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;

const resourcesDir = Path.resolve(__dirname, 'src', 'main', 'assets')

const outputDir =
  process.env.FROM_SBT === 'true'
    ? Path.resolve(__dirname, 'target', 'web', 'public', 'main', 'build')
    : Path.resolve(__dirname, 'dist')

module.exports = {
  entry: {
    app: Path.resolve(resourcesDir, 'appEntry.js'),
  },
  output: {
    path: outputDir,
    filename: '[name].js',
    publicPath: '/',
  },
  plugins: [
    new VueLoaderPlugin(),
    new MiniCssExtractPlugin(),
    // new BundleAnalyzerPlugin()
  ],
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: 'vue-loader',
      },
      {
        test: /\.js$/,
        loader: 'babel-loader',
        include: resourcesDir,
        options: {
          presets: ['@babel/preset-env'],
        },
      },
      {
        test: /\.css$/,
        use: [
          process.env.NODE_ENV !== 'production' && process.env.FROM_SBT !== 'true'
            ? 'style-loader'
            : MiniCssExtractPlugin.loader,
          'css-loader',
          'postcss-loader',
        ],
      },
      {
        test: /\.scss$/,
        use: [
          process.env.NODE_ENV !== 'production' && process.env.FROM_SBT !== 'true'
            ? 'style-loader'
            : MiniCssExtractPlugin.loader,
          'css-loader',
          'postcss-loader',
          'sass-loader',
        ],
      },
      {
        test: /\.(png|jpe?g|gif|svg)$/i,
        use: [
          {
            loader: 'file-loader',
            options: {
              publicPath: process.env.FROM_SBT === 'true' ? '/assets/build' : undefined,
              esModule: false,
            },
          },
        ],
      },
    ],
  },
  resolve: {
    extensions: ['.js', '.vue'],
    alias: {
      vue$: 'vue/dist/vue.esm.js',
    },
  },
  devServer: {
    historyApiFallback: true,
  },
  optimization: {
    splitChunks: {
      cacheGroups: {
        vendors: {
          name: 'vendors',
          chunks: 'initial',
          test: /[\\/]node_modules[\\/]/,
          priority: 10,
          enforce: true,
        },
        commons: {
          name: 'commons',
          chunks: 'initial',
          minChunks: 2,
        },
      },
    },
  },
}
