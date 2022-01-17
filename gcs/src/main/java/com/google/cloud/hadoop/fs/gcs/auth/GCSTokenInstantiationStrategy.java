package com.google.cloud.hadoop.fs.gcs.auth;

public enum GCSTokenInstantiationStrategy {
  // Token instance is created per service. Default behavior
  INSTANCE_PER_SERVICE,
  // Token instance is shared across all services. This allows token sharing across multiple GCS
  // filesystems
  SHARED,
}
