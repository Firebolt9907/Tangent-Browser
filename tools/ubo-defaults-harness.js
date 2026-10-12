// Run after ./gradlew :app:prepareBundledUblock. Exercises upstream uBO's selection code.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../app/build/generated/ublockAssets/extensions/ublock');
const index = JSON.parse(fs.readFileSync(path.join(root, 'assets/assets.json'), 'utf8'));
const storage = fs.readFileSync(path.join(root, 'js/storage.js'), 'utf8');
const methods = ['autoSelectRegionalFilterLists', 'loadSelectedFilterLists'];
const source = methods.map(name => {
    const start = storage.indexOf(`µb.${name} = `);
    assert(start >= 0, `upstream method ${name} missing`);
    const end = storage.indexOf('\n};', start);
    assert(end > start);
    return storage.slice(start, end + 3);
}).join('\n');
let saved;
const ubo = {
    userFiltersPath: 'user-filters',
    listMatchesEnvironment: () => false,
    saveSelectedFilterLists(keys) { saved = keys; this.selectedFilterLists = keys; },
};
const context = vm.createContext({
    Object, // storage stubs return objects from the host realm
    µb: ubo,
    vAPI: { storage: { get: async () => ({}) } },
    io: { metadata: async () => index },
});
vm.runInContext(source, context);
(async () => {
    await ubo.loadSelectedFilterLists();
    for (const key of ['easylist', 'easyprivacy', 'ublock-filters', 'fanboy-cookiemonster', 'ublock-cookies-easylist']) {
        assert(saved.includes(key), `${key} absent from first-run defaults`);
    }
    for (const key of ['fanboy-cookiemonster', 'ublock-cookies-easylist']) {
        const local = index[key].contentURL.find(url => url.startsWith('assets/tangent/'));
        assert(local, `${key} missing offline fallback`);
        assert(fs.readFileSync(path.join(root, local), 'utf8').length > 10000);
        assert(index[key].contentURL.some(url => url.startsWith('https://')), 'keep live filter updates');
    }
    const custom = ['user-filters', 'easylist'];
    context.vAPI.storage.get = async () => ({ selectedFilterLists: custom });
    saved = undefined;
    await ubo.loadSelectedFilterLists();
    assert.equal(saved, undefined, 'saved choices must not be overwritten');
    assert.equal(ubo.selectedFilterLists, custom);
    assert(!fs.existsSync(path.join(root, 'META-INF')), 'modified package must not claim AMO signature');
    assert(fs.existsSync(path.join(root, 'LICENSE.txt')));
    console.log('PASS: default protection, offline cookie lists, live list URLs, and persisted user choices');
})().catch(error => { console.error(error); process.exitCode = 1; });
