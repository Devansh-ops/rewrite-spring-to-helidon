# Keep the canonical migration generic and fail-closed

The canonical migration contains only generalized transformations with explicit, behavior-preserving
boundaries; when those boundaries cannot be established, it preserves the Spring construct and
reports a refusal. Transformations that require security, persistence, configuration, deployment,
or other application policy remain separately activatable and configurable, because broader default
coverage is not worth silently changing runtime behavior.
