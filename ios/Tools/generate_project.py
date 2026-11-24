#!/usr/bin/env python3
"""Generate the small, deterministic Xcode project without requiring XcodeGen."""
import hashlib, json, pathlib, plistlib
root = pathlib.Path(__file__).resolve().parents[1]
def key(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def q(value): return json.dumps(str(value))
objects = {}
def obj(object_key, isa, **fields):
    uid = key(object_key); objects[uid] = {'isa': isa, **fields}; return uid
class Ref(str): pass
def ref(value): return Ref(value)
def refs(values): return [ref(value) for value in values]
files = sorted((root/'AmorX').glob('*.swift')) + sorted((root/'AmorXCore').glob('*.swift'))
resources = sorted((root/'AmorX/Resources').glob('*')) + [root/'AmorX/Assets.xcassets']
source_refs, source_build, resource_refs, resource_build = [], [], [], []
for group, build, paths in [(source_refs, source_build, files), (resource_refs, resource_build, resources)]:
    for path in paths:
        relative = str(path.relative_to(root)); kind = 'sourcecode.swift' if path.suffix == '.swift' else 'folder.assetcatalog' if path.suffix == '.xcassets' else 'file'
        file = obj(relative, 'PBXFileReference', lastKnownFileType=kind, path=relative, sourceTree='SOURCE_ROOT')
        group.append(file); build.append(obj(relative+'-build', 'PBXBuildFile', fileRef=ref(file)))
product = obj('product', 'PBXFileReference', explicitFileType='wrapper.application', includeInIndex=0, path='AmorX.app', sourceTree='BUILT_PRODUCTS_DIR')
products = obj('products', 'PBXGroup', children=refs([product]), name='Products', sourceTree='<group>')
source_group = obj('sources', 'PBXGroup', children=refs(source_refs), name='Sources', sourceTree='<group>')
resources_group = obj('resources', 'PBXGroup', children=refs(resource_refs), name='Resources', sourceTree='<group>')
main_group = obj('main', 'PBXGroup', children=refs([source_group, resources_group, products]), sourceTree='<group>')
package = obj('YouTubeKit', 'XCLocalSwiftPackageReference', relativePath='Vendor/YouTubeKit')
package_product = obj('YouTubeKit-product', 'XCSwiftPackageProductDependency', package=ref(package), productName='YouTubeKit')
package_build = obj('YouTubeKit-build', 'PBXBuildFile', productRef=ref(package_product))
sources_phase = obj('sources-phase', 'PBXSourcesBuildPhase', buildActionMask=2147483647, files=refs(source_build), runOnlyForDeploymentPostprocessing=0)
resource_phase = obj('resources-phase', 'PBXResourcesBuildPhase', buildActionMask=2147483647, files=refs(resource_build), runOnlyForDeploymentPostprocessing=0)
framework_phase = obj('framework-phase', 'PBXFrameworksBuildPhase', buildActionMask=2147483647, files=refs([package_build]), runOnlyForDeploymentPostprocessing=0)
project_configs, target_configs = [], []
for mode in ['Debug', 'Release']:
    project_settings = {'ALWAYS_SEARCH_USER_PATHS': 'NO', 'CLANG_ENABLE_MODULES': 'YES', 'CLANG_ENABLE_OBJC_ARC': 'YES', 'IPHONEOS_DEPLOYMENT_TARGET': '15.0', 'SDKROOT': 'iphoneos', 'SWIFT_VERSION': '5.0', 'SWIFT_STRICT_CONCURRENCY': 'minimal', 'ENABLE_USER_SCRIPT_SANDBOXING': 'YES', 'DEBUG_INFORMATION_FORMAT': 'dwarf-with-dsym' if mode=='Release' else 'dwarf', 'SWIFT_OPTIMIZATION_LEVEL': '-O' if mode=='Release' else '-Onone', 'SWIFT_COMPILATION_MODE': 'wholemodule' if mode=='Release' else 'incremental'}
    if mode=='Debug': project_settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = 'DEBUG'
    project_configs.append(obj('project-'+mode, 'XCBuildConfiguration', buildSettings=project_settings, name=mode))
    target_settings = {'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIcon', 'CODE_SIGNING_ALLOWED': 'NO', 'CURRENT_PROJECT_VERSION': '1329', 'MARKETING_VERSION': '5.5.499', 'PRODUCT_BUNDLE_IDENTIFIER': 'com.amorx.ios', 'PRODUCT_NAME': 'AmorX', 'TARGETED_DEVICE_FAMILY': '1,2', 'INFOPLIST_FILE': 'AmorX/Info.plist', 'GENERATE_INFOPLIST_FILE': 'NO', 'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator', 'SUPPORTS_MACCATALYST': 'NO', 'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD': 'NO', 'LD_RUNPATH_SEARCH_PATHS': '$(inherited) @executable_path/Frameworks', 'ENABLE_BITCODE': 'NO', 'SWIFT_EMIT_LOC_STRINGS': 'YES'}
    target_configs.append(obj('target-'+mode, 'XCBuildConfiguration', buildSettings=target_settings, name=mode))
pconfigs = obj('project-configs', 'XCConfigurationList', buildConfigurations=refs(project_configs), defaultConfigurationIsVisible=0, defaultConfigurationName='Release')
tconfigs = obj('target-configs', 'XCConfigurationList', buildConfigurations=refs(target_configs), defaultConfigurationIsVisible=0, defaultConfigurationName='Release')
target = obj('app-target', 'PBXNativeTarget', buildConfigurationList=ref(tconfigs), buildPhases=refs([sources_phase, framework_phase, resource_phase]), buildRules=[], dependencies=[], name='AmorX', packageProductDependencies=refs([package_product]), productName='AmorX', productReference=ref(product), productType='com.apple.product-type.application')
project = obj('project', 'PBXProject', attributes={'BuildIndependentTargetsInParallel': 'YES', 'LastSwiftUpdateCheck': '1640', 'LastUpgradeCheck': '1640'}, buildConfigurationList=ref(pconfigs), compatibilityVersion='Xcode 14.0', developmentRegion='ru', hasScannedForEncodings=0, knownRegions=['ru', 'en', 'Base'], mainGroup=ref(main_group), packageReferences=refs([package]), productRefGroup=ref(products), projectDirPath='', projectRoot='', targets=refs([target]))
def render(value, depth=0):
    if isinstance(value, Ref): return value
    if isinstance(value, dict): return '{\n'+''.join('\t'*(depth+1)+q(k)+' = '+render(v, depth+1)+';\n' for k,v in value.items())+'\t'*depth+'}'
    if isinstance(value, list): return '('+', '.join(render(v, depth) for v in value)+')'
    return str(value) if isinstance(value, int) else q(value)
folder=root/'AmorX.xcodeproj'; folder.mkdir(exist_ok=True)
(folder/'project.pbxproj').write_text('// !$*UTF8*$!\n'+render({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':{Ref(k):v for k,v in objects.items()},'rootObject':ref(project)})+'\n')
scheme=folder/'xcshareddata/xcschemes'; scheme.mkdir(parents=True,exist_ok=True)
(scheme/'AmorX.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1640" version="1.3"><BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="AmorX.app" BlueprintName="AmorX" ReferencedContainer="container:AmorX.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction><TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"/><LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="AmorX.app" BlueprintName="AmorX" ReferencedContainer="container:AmorX.xcodeproj"/></BuildableProductRunnable></LaunchAction><ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"/><AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/></Scheme>
''')
print('Generated Xcode project:',len(files),'Swift files')
