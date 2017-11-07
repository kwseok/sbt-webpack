const path = require('path')

module.exports = {
  context: path.join(__dirname, '/src/main/web'),
  entry: './js/entry.js',
  output: {
    path: path.join(__dirname, '/src/main/public/bundles'),
    filename: 'bundle.js'
  }
}
