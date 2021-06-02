/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.apt.datastore;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.apt.internal.AptFacetHelper;
import org.sonatype.nexus.repository.apt.internal.AptFormat;
import org.sonatype.nexus.repository.apt.internal.AptPackageParser;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.content.utils.FormatAttributesUtils;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;
import static org.sonatype.nexus.repository.apt.internal.AptFacetHelper.normalizeAssetPath;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.DEB;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_ARCHITECTURE;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_INDEX_SECTION;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_PACKAGE_NAME;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_PACKAGE_VERSION;
import static org.sonatype.nexus.repository.apt.internal.debian.Utils.isDebPackageContentType;
import static org.sonatype.nexus.repository.storage.AssetEntityAdapter.P_ASSET_KIND;

/**
 * Apt content facet
 *
 * @since 3.next
 */
@Facet.Exposed
@Named(AptFormat.NAME)
public class AptContentFacet
    extends ContentFacetSupport
{
  @VisibleForTesting
  static final String CONFIG_KEY = "apt";

  @Inject
  public AptContentFacet(
      @Named(AptFormat.NAME) final FormatStoreManager formatStoreManager)
  {
    super(formatStoreManager);
  }

  static class Config
  {
    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public String distribution;

    @NotNull(groups = {ProxyType.ValidationGroup.class})
    public boolean flat;
  }

  private Config config;

  @Override
  protected WritePolicy writePolicy(final Asset asset) {
    WritePolicy writePolicy = super.writePolicy(asset);
    if (WritePolicy.ALLOW_ONCE == writePolicy) {
      String name = asset.path();
      if (name.endsWith(".deb")) {
        return WritePolicy.ALLOW_ONCE;
      }
      else {
        return WritePolicy.ALLOW;
      }
    }
    return writePolicy;
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    config = facet(ConfigurationFacet.class)
        .readSection(configuration, CONFIG_KEY, Config.class);
    log.debug("APT config: {}", config);
  }
  
  public String getDistribution() {
    return config.distribution;
  }

  public boolean isFlat() {
    return config.flat;
  }

  public Optional<FluentAsset> getAsset(final String path) {
    return assets().path(normalizeAssetPath(path)).find();
  }

  public Optional<Content> get(final String assetPath) {
    return assets().path(normalizeAssetPath(assetPath)).find().map(FluentAsset::download);
  }

  public FluentAsset put(final String path, final Payload content) throws IOException {
    return put(path, content, null);
  }

  public FluentAsset put(final String path,
                         final Payload payload,
                         @Nullable final PackageInfo packageInfo) throws IOException
  {
    String normalizedPath = normalizeAssetPath(path);

    try (TempBlob tempBlob = blobs().ingest(payload, AptFacetHelper.hashAlgorithms)) {
      return isDebPackageContentType(normalizedPath)
          ? findOrCreateDebAsset(normalizedPath, tempBlob, packageInfo)
          : findOrCreateMetadataAsset(tempBlob, normalizedPath);
    }
  }

  public FluentAsset findOrCreateDebAsset(final String path, final TempBlob tempBlob, final PackageInfo packageInfo)
      throws IOException
  {
    final ControlFile controlFile = AptPackageParser.parsePackage(() -> tempBlob.getBlob().getInputStream());
    PackageInfo info = packageInfo != null
        ? packageInfo
        : new PackageInfo(controlFile);

    FluentAsset asset = assets()
        .path(normalizeAssetPath(path))
        .kind(DEB)
        .component(findOrCreateComponent(info))
        .blob(tempBlob).save();

    populateAttributes(info, asset, controlFile);

    return asset;
  }

  private void populateAttributes(final PackageInfo info, final FluentAsset asset, final ControlFile controlFile) {
    final Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put(P_ARCHITECTURE, info.getArchitecture());
    formatAttributes.put(P_PACKAGE_NAME, info.getPackageName());
    formatAttributes.put(P_PACKAGE_VERSION, info.getVersion());
    formatAttributes.put(P_INDEX_SECTION, buildIndexSection(controlFile, asset));
    formatAttributes.put(P_ASSET_KIND, DEB);

    FormatAttributesUtils.setFormatAttributes(asset, formatAttributes);
  }

  private String buildIndexSection(final ControlFile controlFile, final FluentAsset asset) {
    AssetBlob assetBlob = asset.blob()
        .orElseThrow(() -> new IllegalStateException(
            "Impossible build " + P_INDEX_SECTION + ". Asset blob couldn't be found for asset: " + asset.path()));
    final Map<String, String> checksums = assetBlob.checksums();

    return controlFile.getParagraphs().get(0)
        .withFields(Arrays.asList(
            new ControlFile.ControlField("Filename", asset.path()),
            new ControlFile.ControlField("Size", Long.toString(assetBlob.blobSize())),
            new ControlFile.ControlField("MD5Sum", checksums.get(MD5.name())),
            new ControlFile.ControlField("SHA1", checksums.get(SHA1.name())),
            new ControlFile.ControlField("SHA256", checksums.get(SHA256.name()))))
        .toString();
  }

  public FluentAsset findOrCreateMetadataAsset(final TempBlob tempBlob, final String path) {
    return assets()
        .path(normalizeAssetPath(path))
        .blob(tempBlob)
        .save();
  }

  private FluentComponent findOrCreateComponent(final PackageInfo info) {
    String name = info.getPackageName();
    String version = info.getVersion();
    String architecture = info.getArchitecture();

    return components()
        .name(name)
        .version(version)
        .namespace(architecture)
        .getOrCreate();
  }

  public TempBlob getTempBlob(final Payload payload) {
    checkNotNull(payload);
    return blobs().ingest(payload, AptFacetHelper.hashAlgorithms);
  }
}