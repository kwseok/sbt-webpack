const path = require('path');

module.exports = {
  context: path.join(__dirname, '/src/main/assets'),
  entry: './javascripts/entry.js',
  output: {
    path: path.join(__dirname, '/target/web/webpack'),
    filename: 'javascripts/bundle.js'
  }
};
