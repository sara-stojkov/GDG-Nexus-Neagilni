import torch

class StandardScaler:
    def __init__(self):
        self.mean = None
        self.std = None
        self.eps = 1e-6

    def fit(self, x: torch.Tensor):
        """
        x: [T, F]
        """
        self.mean = x.mean(dim=0)
        self.std = x.std(dim=0) + self.eps
        return self

    def transform(self, x: torch.Tensor):
        return (x - self.mean) / self.std

    def fit_transform(self, x: torch.Tensor):
        return self.fit(x).transform(x)
