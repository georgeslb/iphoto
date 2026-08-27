package org.iphoto.model.request;

// name: the person name typed against a face in the Lightbox. confirmMerge: the caller has
// already seen the "a person with this name already exists" prompt and confirmed attaching this
// one face to that existing person, rather than the request being a first attempt.
public record FaceRenameRequest(String name, boolean confirmMerge) {}
