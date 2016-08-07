const path = require('path');
const srcDir = path.join(__dirname, '/src/main/assets');
const outDir = path.join(__dirname, '/target/web/webpack/main');

module.exports = {
  entry: path.join(srcDir, 'javascripts/entry.js'),
  output: {
    path: outDir,
    filename: 'javascripts/bundle.js'
  }
};
