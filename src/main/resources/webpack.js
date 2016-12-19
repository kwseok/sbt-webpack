(function() {
  'use strict';

  const _ = require('lodash');
  const webpack = require('webpack');
  const options = require(process.argv[2]);

  const primaryOptions = Array.isArray(options) ? options[0] : options;

  const defaults = _.partialRight(_.mergeWith, function(obj, src) {
    if (_.isPlainObject(obj)) {
      return defaults(obj, src);
    }
    return _.isUndefined(src) ? obj : src;
  });

  defaults(primaryOptions, JSON.parse(decodeURIComponent(process.argv[3] || '{}')));

  const outputOptions = defaults({
    colors: true,
    cached: false,
    cachedAssets: false,
    modules: true,
    chunks: false,
    reasons: false,
    errorDetails: false,
    chunkOrigins: false,
    exclude: ['node_modules', 'bower_components', 'jam', 'components']
  }, options.stats || primaryOptions.stats || {});

  Error.stackTraceLimit = 30;

  var lastHash = null;
  var compiler = webpack(options);

  function compilerCallback(err, stats) {
    if (!primaryOptions.watch) {
      // Do not keep cache anymore
      compiler.purgeInputFileSystem();
    }
    if (err) {
      lastHash = null;
      console.error(err.stack || err);
      if (err.details) console.error(err.details);
      if (!primaryOptions.watch) {
        process.on('exit', function() {
          process.exit(1); // eslint-disable-line
        });
      }
      return
    }
    if (stats.hash !== lastHash) {
      lastHash = stats.hash;
      console.log(stats.toString(outputOptions));
      if (!primaryOptions.watch) {
        console.log('\u0010' + JSON.stringify(lastHash));
      }
    }
    if (!primaryOptions.watch && stats.hasErrors()) {
      process.on('exit', function() {
        process.exit(1); // eslint-disable-line
      });
    }
  }

  if (primaryOptions.watch) {
    var watchOptions = primaryOptions.watchOptions || {};
    if (watchOptions.stdin) {
      process.stdin.on('end', function() {
        process.exit(0); // eslint-disable-line
      });
      process.stdin.resume();
    }
    compiler.watch(watchOptions, compilerCallback);
  } else {
    compiler.run(compilerCallback);
  }
})();
