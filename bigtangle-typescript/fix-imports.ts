const fs = require('fs');
const path = require('path');

// Map Java package names to TypeScript paths
const importMap = {
    'net.bigtangle.core.exception.': './exception/',
    'net.bigtangle.core.utils.': './utils/',
    'net.bigtangle.core.': './',
    'net.bigtangle.': '../',
    'org.': '../../org/'
};

const projectRoot = path.join(__dirname, 'src', 'net', 'bigtangle');
const allFiles: string[] = [];

function walkDir(dir: string) {
    const files = fs.readdirSync(dir);
    for (const file of files) {
        const filePath = path.join(dir, file);
        const stat = fs.statSync(filePath);
        if (stat.isDirectory()) {
            walkDir(filePath);
        } else if (file.endsWith('.ts')) {
            allFiles.push(filePath);
        }
    }
}

walkDir(projectRoot);

for (const file of allFiles) {
    let content = fs.readFileSync(file, 'utf-8');
    let updated = false;
    
    // Use appropriate import map based on file location
    const isTestFile = file.includes('/test/');
    const currentMap = isTestFile ? {...importMap, ...{
        '../': './', // For test files
        '../../': '../', // For test files
    }} : importMap;
    
    // Fix import paths
    for (const [javaPrefix, tsPath] of Object.entries(currentMap)) {
        const regex = new RegExp(`from ['"]${javaPrefix.replace(/\./g, '\\.')}(\\w+)['"]`, 'g');
        const matches = content.match(regex);
        
        if (matches) {
            updated = true;
            content = content.replace(regex, `from '${tsPath}$1'`);
        }
    }
    
    // Remove .js extension from imports
    content = content.replace(/from '(\..*?)\.js';/g, "from '$1';");
    
    // Add missing .ts extensions for test files
    if (isTestFile) {
        content = content.replace(/from '(\.\.?\/[^';]+)';/g, (match: string, p1: string) => {
            if (!p1.endsWith('.ts') && !p1.endsWith('.js')) {
                return `from '${p1}.ts';`;
            }
            return match;
        });
    }
    
    // Fix Utils method calls
    const utilsMethods = ['writeNBytesString', 'readNBytesString', 'doubleDigest', 'arraysEqual', 'UTF8'];
    for (const method of utilsMethods) {
        if (content.includes(`Utils.${method}`)) {
            updated = true;
            content = content.replace(new RegExp(`Utils\\.${method}`, 'g'), `Utils.${method}`);
        }
    }
    
    if (updated) {
        fs.writeFileSync(file, content);
        console.log(`Updated imports in ${path.relative(projectRoot, file)}`);
    }
}

console.log('Import fixes completed!');
