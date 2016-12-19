(function() {
    'use strict';

    var options = require(process.argv[2]);

    if (!Array.isArray(options)) {
        options = [options];
    }

    var contexts = options.map(function(option, i) {
        if (!option.context) {
            throw new Error('Webpack options[' + i + '].context is required');
        }
        return option.context;
    });

    console.log('\u0010' + JSON.stringify(contexts));
})();
