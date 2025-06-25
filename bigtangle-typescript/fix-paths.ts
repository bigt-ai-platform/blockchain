import fs from 'fs';
import path from 'path';

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

const importMap = {
    'net.bigtangle.core.exception.': './exception/',
    'net.bigtangle.core.utils.': './utils/',
    'net.bigtangle.core.': './',
    'net.bigtangle.': '../',
    'org.': '../../org/'
};

const testImportMap = {
    '../': './', // For test files
    '../../': '../', // For test files
};

for (const file of allFiles) {
    let content = fs.readFileSync(file, 'utf-8');
    let updated = false;
    
    // Use appropriate import map based on file location
    const isTestFile = file.includes('/test/');
    const currentMap = isTestFile ? {...importMap, ...testImportMap} : importMap;
    
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
        content = content.replace(/from '(\.\.?\/[^';]+)';/g, (match, p1) => {
            if (!p1.endsWith('.ts') && !p1.endsWith('.js')) {
                return `from '${p1}.ts';`;
            }
            return match;
        });
    }
    
    if (updated) {
        fs.writeFileSync(file, content);
        console.log(`Updated imports in ${path.relative(projectRoot, file)}`);
    }
}

console.log('Import path fixes completed!');
