export class CatalogError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CatalogError';
  }
}

export class NotFoundError extends CatalogError {
  constructor(message: string) {
    super(message);
    this.name = 'NotFoundError';
  }
}

export class LoadError extends CatalogError {
  constructor(message: string) {
    super(message);
    this.name = 'LoadError';
  }
}

export class DuplicateIdError extends CatalogError {
  constructor(message: string) {
    super(message);
    this.name = 'DuplicateIdError';
  }
}
